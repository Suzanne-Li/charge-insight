package com.chargeinsight.agent.chat;

import com.chargeinsight.agent.planning.AnalysisPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentChatSessionService {
    private final JdbcTemplate jdbcTemplate;
    private final AgentShortTermMemoryService shortTermMemory;
    private final AgentLongTermMemoryService longTermMemory;
    private final ObjectMapper objectMapper;
    private final int summaryTriggerMessageCount;
    private final int contextWindowMessageLimit;
    private final SessionSummaryCompactor summaryCompactor = new SessionSummaryCompactor();

    public AgentChatSessionService(JdbcTemplate jdbcTemplate, AgentShortTermMemoryService shortTermMemory) {
        this(jdbcTemplate, shortTermMemory, null, null, 12, 8);
    }

    @Autowired
    public AgentChatSessionService(JdbcTemplate jdbcTemplate, AgentShortTermMemoryService shortTermMemory,
            AgentLongTermMemoryService longTermMemory, ObjectMapper objectMapper,
            @Value("${charge.memory.summary-trigger-message-count:12}") int summaryTriggerMessageCount,
            @Value("${charge.memory.context-window-message-limit:8}") int contextWindowMessageLimit) {
        this.jdbcTemplate = jdbcTemplate;
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.objectMapper = objectMapper;
        this.summaryTriggerMessageCount = Math.max(3, summaryTriggerMessageCount);
        this.contextWindowMessageLimit = Math.max(2, contextWindowMessageLimit);
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
        compactIfNeeded(sessionId);
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
        StringBuilder context = new StringBuilder(question);
        if (needsContext(question) && session.region() != null) {
            context.append("\n\n会话中已确认的查询范围：区域=").append(session.region());
            if (session.city() != null) context.append("，城市=").append(session.city());
            if (session.group() != null) context.append("，桩群=").append(session.group());
            if (session.timeRange() != null) context.append("，上一轮时间范围=").append(session.timeRange());
            context.append("。只继承用户本轮未重新指定的范围。");
            List<AgentShortTermMemoryService.MemoryMessage> recent = recentWithMysqlFallback(sessionId);
            if (!recent.isEmpty()) {
                context.append("\n最近会话摘要：");
                recent.stream().skip(Math.max(0, recent.size() - contextWindowMessageLimit)).forEach(message -> context.append("\n")
                        .append(message.role()).append("：").append(abbreviate(message.content())));
            }
            appendStructuredSummary(context, sessionId);
        }
        if (longTermMemory != null) {
            String memoryContext = longTermMemory.recallContext(question);
            if (!memoryContext.isBlank()) context.append("\n\n").append(memoryContext);
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

    private void compactIfNeeded(String sessionId) {
        if (objectMapper == null) return;
        List<Message> persisted = messages(sessionId);
        if (!summaryCompactor.shouldCompact(persisted.size(), summaryTriggerMessageCount)) return;
        List<Message> compacted = summaryCompactor.messagesToCompact(persisted, contextWindowMessageLimit);
        if (compacted.isEmpty()) return;
        SessionSummaryCompactor.StructuredSummary summary = summaryCompactor.compact(compacted, requireOwned(sessionId));
        List<String> existing = jdbcTemplate.query("SELECT source_hash FROM analytics_session_summary WHERE session_id=?",
                (row, ignored) -> row.getString(1), sessionId);
        if (!existing.isEmpty() && summary.sourceHash().equals(existing.get(0))) return;
        try {
            jdbcTemplate.update("""
                    INSERT INTO analytics_session_summary(session_id, covered_through_message_id, source_hash, summary_json)
                    VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE covered_through_message_id=VALUES(covered_through_message_id),
                        source_hash=VALUES(source_hash), summary_json=VALUES(summary_json)
                    """, sessionId, summary.coveredThroughMessageId(), summary.sourceHash(),
                    objectMapper.writeValueAsString(summary));
        } catch (Exception exception) {
            throw new IllegalStateException("会话结构化摘要保存失败", exception);
        }
    }

    private void appendStructuredSummary(StringBuilder context, String sessionId) {
        if (objectMapper == null) return;
        List<String> summaries = jdbcTemplate.query("SELECT summary_json FROM analytics_session_summary WHERE session_id=?",
                (row, ignored) -> row.getString(1), sessionId);
        if (summaries.isEmpty()) return;
        try {
            SessionSummaryCompactor.StructuredSummary summary = objectMapper.readValue(summaries.get(0),
                    SessionSummaryCompactor.StructuredSummary.class);
            context.append("\n早期结构化会话摘要：范围=").append(summary.confirmedScope());
            if (!summary.userRequests().isEmpty()) context.append("；历史问题=").append(summary.userRequests());
            if (!summary.verifiedConclusions().isEmpty()) context.append("；历史结论=").append(summary.verifiedConclusions());
        } catch (Exception ignored) {
            // A malformed derived summary must never prevent MySQL-backed chat from continuing.
        }
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
