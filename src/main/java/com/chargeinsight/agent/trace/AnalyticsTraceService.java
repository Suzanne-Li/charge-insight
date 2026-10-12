package com.chargeinsight.agent.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only access to persisted agent runs. Context is intentionally excluded from the public API. */
@Service
public class AnalyticsTraceService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AnalyticsTraceService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new ObjectMapper().findAndRegisterModules());
    }

    @Autowired
    public AnalyticsTraceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** Creates either a root trace or an MCP child trace correlated to an existing Agent trace. */
    public TraceContext start(String question, String correlationId) {
        if (correlationId != null && !correlationId.isBlank()) validateTraceId(correlationId);
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String safeCorrelationId = correlationId == null || correlationId.isBlank() ? traceId : correlationId;
        String parentTraceId = traceId.equals(safeCorrelationId) ? null : safeCorrelationId;
        jdbcTemplate.update("""
                INSERT INTO analytics_agent_trace(trace_id, correlation_id, parent_trace_id, question, status, started_at)
                VALUES (?, ?, ?, ?, 'RUNNING', NOW())
                """, traceId, safeCorrelationId, parentTraceId, question);
        return new TraceContext(traceId, safeCorrelationId, parentTraceId);
    }

    public void recordStep(String traceId, int number, String type, String summary) {
        jdbcTemplate.update("""
                INSERT INTO analytics_agent_trace_step(trace_id, step_number, step_type, summary)
                VALUES (?, ?, ?, ?)
                """, traceId, number, type, summary);
    }

    public void recordTool(String traceId, int number, String toolName, Map<String, Object> parameters,
                           long durationMs, String resultSummary) {
        jdbcTemplate.update("""
                INSERT INTO analytics_agent_trace_step(trace_id, step_number, step_type, tool_name,
                    parameters_json, duration_ms, result_summary, summary)
                VALUES (?, ?, 'TOOL_CALL', ?, ?, ?, ?, ?)
                """, traceId, number, toolName, safeJson(parameters), durationMs, resultSummary,
                "tool=" + toolName + ", " + resultSummary);
    }

    public void finish(String traceId, String status) {
        jdbcTemplate.update("UPDATE analytics_agent_trace SET status=?, completed_at=NOW() WHERE trace_id=?", status, traceId);
    }

    public List<TraceSummary> list(Integer limit) {
        int safeLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (safeLimit < 1 || safeLimit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit 必须在 1 到 " + MAX_LIMIT + " 之间");
        }
        return jdbcTemplate.query("""
                SELECT trace_id, correlation_id, parent_trace_id, question, status, started_at, completed_at, created_at
                FROM analytics_agent_trace
                ORDER BY created_at DESC
                LIMIT ?
                """, (row, ignored) -> new TraceSummary(row.getString("trace_id"), row.getString("correlation_id"), row.getString("parent_trace_id"), row.getString("question"),
                row.getString("status"), instant(row.getTimestamp("started_at")), instant(row.getTimestamp("completed_at")),
                instant(row.getTimestamp("created_at"))), safeLimit);
    }

    public TraceDetail get(String traceId) {
        validateTraceId(traceId);
        List<TraceDetail> traces = jdbcTemplate.query("""
                SELECT trace_id, correlation_id, parent_trace_id, question, analysis_plan, status, started_at, completed_at, created_at
                FROM analytics_agent_trace WHERE trace_id = ?
                """, (row, ignored) -> new TraceDetail(row.getString("trace_id"), row.getString("correlation_id"), row.getString("parent_trace_id"), row.getString("question"),
                row.getString("analysis_plan"), row.getString("status"), instant(row.getTimestamp("started_at")),
                instant(row.getTimestamp("completed_at")), instant(row.getTimestamp("created_at")), List.of()), traceId);
        if (traces.isEmpty()) {
            throw new TraceNotFoundException(traceId);
        }
        TraceDetail trace = traces.get(0);
        List<TraceStep> steps = jdbcTemplate.query("""
                SELECT step_number, step_type, tool_name, parameters_json, duration_ms, result_summary, summary, created_at
                FROM analytics_agent_trace_step WHERE trace_id = ? ORDER BY created_at, id
                """, (row, ignored) -> new TraceStep(row.getInt("step_number"), row.getString("step_type"),
                row.getString("tool_name"), row.getString("parameters_json"), row.getObject("duration_ms", Long.class),
                row.getString("result_summary"), row.getString("summary"), instant(row.getTimestamp("created_at"))), traceId);
        return new TraceDetail(trace.traceId(), trace.correlationId(), trace.parentTraceId(), trace.question(), trace.analysisPlan(), trace.status(), trace.startedAt(),
                trace.completedAt(), trace.createdAt(), steps);
    }

    public List<TraceSummary> correlation(String correlationId) {
        validateTraceId(correlationId);
        return jdbcTemplate.query("""
                SELECT trace_id, correlation_id, parent_trace_id, question, status, started_at, completed_at, created_at
                FROM analytics_agent_trace WHERE correlation_id=? ORDER BY created_at, trace_id
                """, (row, ignored) -> new TraceSummary(row.getString("trace_id"), row.getString("correlation_id"),
                row.getString("parent_trace_id"), row.getString("question"), row.getString("status"),
                instant(row.getTimestamp("started_at")), instant(row.getTimestamp("completed_at")), instant(row.getTimestamp("created_at"))), correlationId);
    }

    private String safeJson(Map<String, Object> parameters) {
        try {
            String json = objectMapper.writeValueAsString(parameters == null ? Map.of() : parameters);
            return json.length() <= 4000 ? json : json.substring(0, 4000) + "…";
        } catch (Exception exception) {
            return "{\"state\":\"UNSERIALIZABLE\"}";
        }
    }

    private void validateTraceId(String traceId) {
        if (traceId == null || !traceId.matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("traceId 格式无效");
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record TraceContext(String traceId, String correlationId, String parentTraceId) { }
    public record TraceSummary(String traceId, String correlationId, String parentTraceId, String question, String status, Instant startedAt, Instant completedAt, Instant createdAt) { }
    public record TraceDetail(String traceId, String correlationId, String parentTraceId, String question, String analysisPlan, String status, Instant startedAt,
                              Instant completedAt, Instant createdAt, List<TraceStep> steps) { }
    public record TraceStep(int stepNumber, String stepType, String toolName, String parametersJson, Long durationMs,
                            String resultSummary, String summary, Instant createdAt) { }
    public static class TraceNotFoundException extends RuntimeException {
        public TraceNotFoundException(String traceId) { super("未找到 trace：" + traceId); }
    }
}
