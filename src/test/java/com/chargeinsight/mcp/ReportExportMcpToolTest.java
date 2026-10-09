package com.chargeinsight.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.chargeinsight.agent.tool.AnalyticsQueryTools;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReportExportMcpToolTest {
    @Test
    void exportsBoundedOverviewCsvAndWritesSuccessfulTraceWithoutMockito() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        ReportExportMcpTool tool = new ReportExportMcpTool(new FixedOverviewQueryTools(), jdbcTemplate);

        String correlationId = "a".repeat(32);
        ReportExportMcpTool.CsvReport report = tool.exportOperationOverview("华东", "2026-08-20", "2026-08-26", correlationId);

        assertThat(report.traceId()).matches("[a-f0-9]{32}");
        assertThat(report.correlationId()).isEqualTo(correlationId);
        assertThat(report.fileName()).isEqualTo("operation-overview-华东-2026-08-20-2026-08-26.csv");
        assertThat(report.mediaType()).isEqualTo("text/csv; charset=utf-8");
        assertThat(report.csv()).contains("region,start_date,end_date")
                .contains("华东,2026-08-20,2026-08-26,2026-08-26,51,60,52.71,7.29,371,360,5195.500,9078.80,0.1215,0.9704");
        assertThat(jdbcTemplate.statements).hasSize(3);
        assertThat(jdbcTemplate.statements.get(0)).contains("correlation_id", "parent_trace_id");
        assertThat(jdbcTemplate.statements.get(1)).contains("tool_name", "parameters_json", "duration_ms");
        assertThat(jdbcTemplate.statements.get(2)).contains("status=?");
    }

    private static final class FixedOverviewQueryTools extends AnalyticsQueryTools {
        private FixedOverviewQueryTools() { super(null, null, null); }

        @Override
        public OperationOverview queryOperationOverview(String region, LocalDate startDate, LocalDate endDate) {
            return new OperationOverview(region, null, startDate, endDate, endDate, 51, 60,
                    new BigDecimal("52.71"), new BigDecimal("7.29"), 371, 360,
                    new BigDecimal("5195.500"), new BigDecimal("9078.80"),
                    new BigDecimal("0.1215"), new BigDecimal("0.9704"));
        }
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> statements = new ArrayList<>();

        @Override
        public int update(String sql, Object... args) {
            statements.add(sql);
            return 1;
        }
    }
}
