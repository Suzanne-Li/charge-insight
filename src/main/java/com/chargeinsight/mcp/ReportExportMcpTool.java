package com.chargeinsight.mcp;

import com.chargeinsight.agent.tool.AnalyticsQueryTools;
import com.chargeinsight.agent.trace.AnalyticsTraceService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** MCP tool backed by the same region-checked, parameterized analytics query service as the Agent. */
@Service
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
public class ReportExportMcpTool {
    private final AnalyticsQueryTools queryTools;
    private final AnalyticsTraceService traceService;

    public ReportExportMcpTool(AnalyticsQueryTools queryTools, JdbcTemplate jdbcTemplate) {
        this(queryTools, new AnalyticsTraceService(jdbcTemplate));
    }

    @Autowired
    public ReportExportMcpTool(AnalyticsQueryTools queryTools, AnalyticsTraceService traceService) {
        this.queryTools = queryTools;
        this.traceService = traceService;
    }

    @McpTool(name = "export_operation_overview", description = "Export a region operation overview as bounded CSV. Requires the caller to have the requested region scope.")
    public CsvReport exportOperationOverview(
            @McpToolParam(required = true, description = "运营大区，例如华东") String region,
            @McpToolParam(required = true, description = "开始日期，YYYY-MM-DD") String startDate,
            @McpToolParam(required = true, description = "结束日期，YYYY-MM-DD") String endDate,
            @McpToolParam(required = false, description = "可选的 Agent 根 Trace ID；传入后将本次 MCP 调用关联为子 Trace") String correlationId) {
        var trace = traceService.start("MCP_REPORT_EXPORT", correlationId);
        long startedAt = System.nanoTime();
        try {
            AnalyticsQueryTools.OperationOverview overview = queryTools.queryOperationOverview(region, LocalDate.parse(startDate), LocalDate.parse(endDate));
            String csv = csv(overview);
            traceService.recordTool(trace.traceId(), 1, "export_operation_overview",
                    Map.of("region", region.trim(), "startDate", startDate, "endDate", endDate), elapsedMs(startedAt),
                    "report=operation_overview, rows=1");
            traceService.finish(trace.traceId(), "SUCCESS");
            return new CsvReport(trace.traceId(), trace.correlationId(), "operation-overview-" + overview.region() + "-" + overview.startDate() + "-" + overview.endDate() + ".csv",
                    "text/csv; charset=utf-8", csv);
        } catch (RuntimeException exception) {
            traceService.recordStep(trace.traceId(), 1, "MCP_REPORT_EXPORT_FAILED", "MCP 报表导出失败");
            traceService.finish(trace.traceId(), "FAILED");
            throw exception;
        }
    }

    public CsvReport exportOperationOverview(String region, String startDate, String endDate) {
        return exportOperationOverview(region, startDate, endDate, null);
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

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    public record CsvReport(String traceId, String correlationId, String fileName, String mediaType, String csv) {
        public CsvReport(String traceId, String fileName, String mediaType, String csv) {
            this(traceId, null, fileName, mediaType, csv);
        }
    }
}
