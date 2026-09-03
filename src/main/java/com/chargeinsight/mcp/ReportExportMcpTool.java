package com.chargeinsight.mcp;

import com.chargeinsight.agent.tool.AnalyticsQueryTools;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** MCP tool backed by the same region-checked, parameterized analytics query service as the Agent. */
@Service
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
public class ReportExportMcpTool {
    private final AnalyticsQueryTools queryTools;
    private final JdbcTemplate jdbcTemplate;

    public ReportExportMcpTool(AnalyticsQueryTools queryTools, JdbcTemplate jdbcTemplate) {
        this.queryTools = queryTools;
        this.jdbcTemplate = jdbcTemplate;
    }

    @McpTool(name = "export_operation_overview", description = "Export a region operation overview as bounded CSV. Requires the caller to have the requested region scope.")
    public CsvReport exportOperationOverview(
            @McpToolParam(required = true, description = "运营大区，例如华东") String region,
            @McpToolParam(required = true, description = "开始日期，YYYY-MM-DD") String startDate,
            @McpToolParam(required = true, description = "结束日期，YYYY-MM-DD") String endDate) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("INSERT INTO analytics_agent_trace(trace_id, question, status, started_at) VALUES (?, ?, 'RUNNING', NOW())",
                traceId, "MCP_REPORT_EXPORT");
        try {
            AnalyticsQueryTools.OperationOverview overview = queryTools.queryOperationOverview(region, LocalDate.parse(startDate), LocalDate.parse(endDate));
            String csv = csv(overview);
            jdbcTemplate.update("INSERT INTO analytics_agent_trace_step(trace_id, step_number, step_type, summary) VALUES (?, 1, 'MCP_REPORT_EXPORT', ?)",
                    traceId, "report=operation_overview, region=" + overview.region() + ", rows=1");
            jdbcTemplate.update("UPDATE analytics_agent_trace SET status='SUCCESS', completed_at=NOW() WHERE trace_id=?", traceId);
            return new CsvReport(traceId, "operation-overview-" + overview.region() + "-" + overview.startDate() + "-" + overview.endDate() + ".csv",
                    "text/csv; charset=utf-8", csv);
        } catch (RuntimeException exception) {
            jdbcTemplate.update("INSERT INTO analytics_agent_trace_step(trace_id, step_number, step_type, summary) VALUES (?, 1, 'MCP_REPORT_EXPORT_FAILED', 'MCP 报表导出失败')", traceId);
            jdbcTemplate.update("UPDATE analytics_agent_trace SET status='FAILED', completed_at=NOW() WHERE trace_id=?", traceId);
            throw exception;
        }
    }

    private String csv(AnalyticsQueryTools.OperationOverview overview) {
        return "region,start_date,end_date,snapshot_date,available_pile_count,total_pile_count,avg_available_pile_count,avg_offline_pile_count,order_count,success_order_count,energy_kwh,gmv_amount,offline_rate,share_success_rate\n"
                + String.join(",", List.of(overview.region(), overview.startDate().toString(), overview.endDate().toString(),
                String.valueOf(overview.snapshotDate()), String.valueOf(overview.availablePileCount()), String.valueOf(overview.totalPileCount()),
                decimal(overview.avgAvailablePileCount()), decimal(overview.avgOfflinePileCount()),
                String.valueOf(overview.orderCount()), String.valueOf(overview.successOrderCount()), decimal(overview.energyKwh()),
                decimal(overview.gmvAmount()), decimal(overview.offlineRate()), decimal(overview.shareSuccessRate()))) + "\n";
    }

    private String decimal(BigDecimal value) { return value.toPlainString(); }

    public record CsvReport(String traceId, String fileName, String mediaType, String csv) { }
}
