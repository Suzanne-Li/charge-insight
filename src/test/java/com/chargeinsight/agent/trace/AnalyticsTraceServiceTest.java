package com.chargeinsight.agent.trace;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AnalyticsTraceServiceTest {
    private final AnalyticsTraceService traceService = new AnalyticsTraceService(null);

    @Test
    void rejectsOutOfRangeListLimitsBeforeDatabaseAccess() {
        assertThatIllegalArgumentException().isThrownBy(() -> traceService.list(0))
                .withMessage("limit 必须在 1 到 100 之间");
        assertThatIllegalArgumentException().isThrownBy(() -> traceService.list(101))
                .withMessage("limit 必须在 1 到 100 之间");
    }

    @Test
    void rejectsMalformedTraceIdBeforeDatabaseAccess() {
        assertThatIllegalArgumentException().isThrownBy(() -> traceService.get("not-a-trace"))
                .withMessage("traceId 格式无效");
    }

    @Test
    void createsChildTraceAndPersistsStructuredToolAudit() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        AnalyticsTraceService service = new AnalyticsTraceService(jdbcTemplate);
        String rootTraceId = "a".repeat(32);

        var trace = service.start("MCP_REPORT_EXPORT", rootTraceId);
        service.recordTool(trace.traceId(), 1, "export_operation_overview", Map.of("region", "华东"), 12, "rows=1");
        service.finish(trace.traceId(), "SUCCESS");

        assertThat(trace.traceId()).matches("[a-f0-9]{32}");
        assertThat(trace.correlationId()).isEqualTo(rootTraceId);
        assertThat(trace.parentTraceId()).isEqualTo(rootTraceId);
        assertThat(jdbcTemplate.statements).hasSize(3);
        assertThat(jdbcTemplate.statements.get(0)).contains("correlation_id", "parent_trace_id");
        assertThat(jdbcTemplate.statements.get(1)).contains("tool_name", "parameters_json", "duration_ms", "result_summary");
        assertThat(jdbcTemplate.arguments.get(1)).contains("export_operation_overview", "rows=1");
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> statements = new ArrayList<>();
        private final List<List<Object>> arguments = new ArrayList<>();

        @Override
        public int update(String sql, Object... args) {
            statements.add(sql);
            arguments.add(List.of(args));
            return 1;
        }
    }
}
