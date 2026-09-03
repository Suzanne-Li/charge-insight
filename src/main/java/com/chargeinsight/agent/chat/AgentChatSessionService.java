package com.chargeinsight.agent.chat;

import com.chargeinsight.agent.planning.AnalysisPlan;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentChatSessionService {
    private final JdbcTemplate jdbcTemplate;
    private final AgentShortTermMemoryService shortTermMemory;

    public AgentChatSessionService(JdbcTemplate jdbcTemplate, AgentShortTermMemoryService shortTermMemory) {
        this.jdbcTemplate = jdbcTemplate;
        this.shortTermMemory = shortTermMemory;
    }

    @Transactional
    public SessionSummary ensureSession(String requestedSessionId, String firstQuestion) {
        if (requestedSessionId != null && !requestedSessionId.isBlank()) return requireOwned(requestedSessionId);
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        String title = title(firstQuestion);
        jdbcTemplate.update("INSERT INTO analytics_chat_session(session_id, owner_username, title) VALUES (?, ?, ?)",
                sessionId, currentUsername(), title);
        return requireOwned(sessionId);
    }

    @Transactional(readOnly = true)
    public List<SessionSummary> list() {
        return jdbcTemplate.query("""
                SELECT session_id, title, scope_region, scope_city, scope_group, scope_time_range, created_at, updated_at
                FROM analytics_chat_session WHERE owner_username = ? ORDER BY updated_at DESC
                """, (row, ignored) -> session(row.getString("session_id"), row.getString("title"),
                row.getString("scope_region"), row.getString("scope_city"), row.getString("scope_group"),
                row.getString("scope_time_range"), row.getTimestamp("created_at"), row.getTimestamp("updated_at")), currentUsername());
    }

    @Transactional(readOnly = true)
    public List<Message> messages(String sessionId) {
        requireOwned(sessionId);
        return jdbcTemplate.query("""
                SELECT id, role, content, trace_id, created_at FROM analytics_chat_message
                WHERE session_id = ? ORDER BY id
                """, (row, ignored) -> new Message(row.getLong("id"), row.getString("role"), row.getString("content"),
                row.getString("trace_id"), instant(row.getTimestamp("created_at"))), sessionId);
    }

    @Transactional
    public void append(String sessionId, String role, String content, String traceId) {
        requireOwned(sessionId);
        if (!List.of("USER", "ASSISTANT").contains(role)) throw new IllegalArgumentException("不支持的消息角色");
        jdbcTemplate.update("INSERT INTO analytics_chat_message(session_id, role, content, trace_id) VALUES (?, ?, ?, ?)",
                sessionId, role, content, traceId);
        jdbcTemplate.update("UPDATE analytics_chat_session SET updated_at=NOW() WHERE session_id=?", sessionId);
        shortTermMemory.append(sessionId, role, content);
    }

    /** Restores prior context before the current message can make an empty cache look populated. */
    @Transactional
    public String prepareUserTurn(String sessionId, String question) {
        String contextualized = contextualize(sessionId, question);
        append(sessionId, "USER", question, null);
        return contextualized;
    }

    @Transactional
    public void updateScope(String sessionId, AnalysisPlan.Scope scope) {
        requireOwned(sessionId);
        jdbcTemplate.update("""
                UPDATE analytics_chat_session SET scope_region=?, scope_city=?, scope_group=?, scope_time_range=?, updated_at=NOW()
                WHERE session_id=?
                """, blankToNull(scope.region()), blankToNull(scope.city()), blankToNull(scope.group()),
                blankToNull(scope.timeRange()), sessionId);
    }

    @Transactional
    public void delete(String sessionId) {
        requireOwned(sessionId);
        jdbcTemplate.update("DELETE FROM analytics_chat_session WHERE session_id=? AND owner_username=?", sessionId, currentUsername());
        shortTermMemory.delete(sessionId);
    }

    public String contextualize(String sessionId, String question) {
        SessionSummary session = requireOwned(sessionId);
        if (!needsContext(question) || session.region() == null) return question;
        StringBuilder context = new StringBuilder(question).append("\n\n会话中已确认的查询范围：区域=").append(session.region());
        if (session.city() != null) context.append("，城市=").append(session.city());
        if (session.group() != null) context.append("，桩群=").append(session.group());
        if (session.timeRange() != null) context.append("，上一轮时间范围=").append(session.timeRange());
        context.append("。只继承用户本轮未重新指定的范围。");
        List<AgentShortTermMemoryService.MemoryMessage> recent = recentWithMysqlFallback(sessionId);
        if (!recent.isEmpty()) {
            context.append("\n最近会话摘要：");
            recent.stream().skip(Math.max(0, recent.size() - 4)).forEach(message -> context.append("\n")
                    .append(message.role()).append("：").append(abbreviate(message.content())));
        }
        return context.toString();
    }

    private List<AgentShortTermMemoryService.MemoryMessage> recentWithMysqlFallback(String sessionId) {
        List<AgentShortTermMemoryService.MemoryMessage> cached = shortTermMemory.recent(sessionId);
        if (!cached.isEmpty()) return cached;
        List<AgentShortTermMemoryService.MemoryMessage> persisted = messages(sessionId).stream()
                .map(message -> new AgentShortTermMemoryService.MemoryMessage(message.role(), message.content())).toList();
        shortTermMemory.refresh(sessionId, persisted);
        return persisted;
    }

    private String abbreviate(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() > 500 ? normalized.substring(0, 500) : normalized;
    }

    private boolean needsContext(String question) {
        return question != null && (question.contains("该区域") || question.contains("这个区域")
                || question.contains("该桩群") || question.contains("这个桩群") || question.contains("上一轮"));
    }

    private SessionSummary requireOwned(String sessionId) {
        if (sessionId == null || !sessionId.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("sessionId 格式无效");
        List<SessionSummary> sessions = jdbcTemplate.query("""
                SELECT session_id, title, scope_region, scope_city, scope_group, scope_time_range, created_at, updated_at
                FROM analytics_chat_session WHERE session_id=? AND owner_username=?
                """, (row, ignored) -> session(row.getString("session_id"), row.getString("title"),
                row.getString("scope_region"), row.getString("scope_city"), row.getString("scope_group"),
                row.getString("scope_time_range"), row.getTimestamp("created_at"), row.getTimestamp("updated_at")),
                sessionId, currentUsername());
        if (sessions.isEmpty()) throw new SessionNotFoundException(sessionId);
        return sessions.get(0);
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) return "local";
        if (authentication.getPrincipal() instanceof Jwt jwt) return jwt.getSubject();
        return authentication.getName();
    }

    private String title(String question) {
        String value = question == null || question.isBlank() ? "新建问数会话" : question.trim().replaceAll("\\s+", " ");
        return value.length() > 60 ? value.substring(0, 60) : value;
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private SessionSummary session(String id, String title, String region, String city, String group, String timeRange,
                                   Timestamp createdAt, Timestamp updatedAt) {
        return new SessionSummary(id, title, region, city, group, timeRange, instant(createdAt), instant(updatedAt));
    }

    public record SessionSummary(String sessionId, String title, String region, String city, String group,
                                 String timeRange, Instant createdAt, Instant updatedAt) { }
    public record Message(long id, String role, String content, String traceId, Instant createdAt) { }
    public static class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(String id) { super("未找到当前账号的问数会话：" + id); }
    }
}
