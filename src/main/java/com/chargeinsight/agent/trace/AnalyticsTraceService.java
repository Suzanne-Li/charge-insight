package com.chargeinsight.agent.trace;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only access to persisted agent runs. Context is intentionally excluded from the public API. */
@Service
public class AnalyticsTraceService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private final JdbcTemplate jdbcTemplate;

    public AnalyticsTraceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TraceSummary> list(Integer limit) {
        int safeLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (safeLimit < 1 || safeLimit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit 必须在 1 到 " + MAX_LIMIT + " 之间");
        }
        return jdbcTemplate.query("""
                SELECT trace_id, question, status, started_at, completed_at, created_at
                FROM analytics_agent_trace
                ORDER BY created_at DESC
                LIMIT ?
                """, (row, ignored) -> new TraceSummary(row.getString("trace_id"), row.getString("question"),
                row.getString("status"), instant(row.getTimestamp("started_at")), instant(row.getTimestamp("completed_at")),
                instant(row.getTimestamp("created_at"))), safeLimit);
    }

    public TraceDetail get(String traceId) {
        validateTraceId(traceId);
        List<TraceDetail> traces = jdbcTemplate.query("""
                SELECT trace_id, question, analysis_plan, status, started_at, completed_at, created_at
                FROM analytics_agent_trace WHERE trace_id = ?
                """, (row, ignored) -> new TraceDetail(row.getString("trace_id"), row.getString("question"),
                row.getString("analysis_plan"), row.getString("status"), instant(row.getTimestamp("started_at")),
                instant(row.getTimestamp("completed_at")), instant(row.getTimestamp("created_at")), List.of()), traceId);
        if (traces.isEmpty()) {
            throw new TraceNotFoundException(traceId);
        }
        TraceDetail trace = traces.get(0);
        List<TraceStep> steps = jdbcTemplate.query("""
                SELECT step_number, step_type, summary, created_at
                FROM analytics_agent_trace_step WHERE trace_id = ? ORDER BY step_number, id
                """, (row, ignored) -> new TraceStep(row.getInt("step_number"), row.getString("step_type"),
                row.getString("summary"), instant(row.getTimestamp("created_at"))), traceId);
        return new TraceDetail(trace.traceId(), trace.question(), trace.analysisPlan(), trace.status(), trace.startedAt(),
                trace.completedAt(), trace.createdAt(), steps);
    }

    private void validateTraceId(String traceId) {
        if (traceId == null || !traceId.matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("traceId 格式无效");
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record TraceSummary(String traceId, String question, String status, Instant startedAt, Instant completedAt, Instant createdAt) { }
    public record TraceDetail(String traceId, String question, String analysisPlan, String status, Instant startedAt,
                              Instant completedAt, Instant createdAt, List<TraceStep> steps) { }
    public record TraceStep(int stepNumber, String stepType, String summary, Instant createdAt) { }
    public static class TraceNotFoundException extends RuntimeException {
        public TraceNotFoundException(String traceId) { super("未找到 trace：" + traceId); }
    }
}
