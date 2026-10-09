package com.chargeinsight.mcp;

import com.chargeinsight.agent.tool.GroupEntityResolver;
import com.chargeinsight.agent.trace.AnalyticsTraceService;
import com.chargeinsight.security.RegionAccessPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Local adapter for future alert/work-order systems; the MCP contract remains stable when an external adapter replaces it. */
@Service
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
public class WorkOrderMcpTool {
    private static final Set<String> SEVERITIES = Set.of("P1", "P2", "P3", "P4");
    private static final Set<String> TARGET_STATUSES = Set.of("IN_PROGRESS", "RESOLVED", "CLOSED");
    private final JdbcTemplate jdbcTemplate;
    private final GroupEntityResolver groupEntityResolver;
    private final RegionAccessPolicy regionAccessPolicy;
    private final AnalyticsTraceService traceService;

    public WorkOrderMcpTool(JdbcTemplate jdbcTemplate, GroupEntityResolver groupEntityResolver, RegionAccessPolicy regionAccessPolicy) {
        this(jdbcTemplate, groupEntityResolver, regionAccessPolicy, new AnalyticsTraceService(jdbcTemplate));
    }

    @Autowired
    public WorkOrderMcpTool(JdbcTemplate jdbcTemplate, GroupEntityResolver groupEntityResolver,
                            RegionAccessPolicy regionAccessPolicy, AnalyticsTraceService traceService) {
        this.jdbcTemplate = jdbcTemplate;
        this.groupEntityResolver = groupEntityResolver;
        this.regionAccessPolicy = regionAccessPolicy;
        this.traceService = traceService;
    }

    @McpTool(name = "create_alert_work_order", description = "Create a local simulated work order for an analytics alert. The caller must have the requested region scope.")
    public WorkOrder createAlertWorkOrder(
            @McpToolParam(required = true, description = "运营大区，例如华东") String region,
            @McpToolParam(required = true, description = "桩群名称") String groupName,
            @McpToolParam(required = true, description = "工单标题，最多160字符") String title,
            @McpToolParam(required = true, description = "优先级：P1、P2、P3、P4") String severity,
            @McpToolParam(required = true, description = "告警或处置说明，最多2000字符") String description,
            @McpToolParam(required = false, description = "可选的 Agent 根 Trace ID；传入后将本次 MCP 调用关联为子 Trace") String correlationId) {
        validate(title, severity, description);
        regionAccessPolicy.assertAllowed(region);
        var trace = traceService.start("MCP_WORK_ORDER_CREATE", correlationId);
        long startedAt = System.nanoTime();
        try {
            GroupEntityResolver.ResolvedGroup group = groupEntityResolver.resolve(region, "", groupName);
            String workOrderNo = "WO-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
            jdbcTemplate.update("INSERT INTO analytics_mcp_work_order(work_order_no, region_name, group_id, group_name, title, description, severity, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN')",
                    workOrderNo, group.region(), group.matchedGroup().groupId(), group.matchedGroup().groupName(), title.trim(), description.trim(), severity.toUpperCase(Locale.ROOT));
            traceService.recordTool(trace.traceId(), 1, "create_alert_work_order",
                    Map.of("region", region.trim(), "groupName", groupName.trim(), "severity", severity.toUpperCase(Locale.ROOT)),
                    elapsedMs(startedAt), "workOrderNo=" + workOrderNo + ", status=OPEN");
            traceService.finish(trace.traceId(), "SUCCESS");
            return new WorkOrder(workOrderNo, group.region(), group.matchedGroup().groupName(), title.trim(), severity.toUpperCase(Locale.ROOT), "OPEN", trace.traceId(), trace.correlationId());
        } catch (RuntimeException exception) {
            traceService.recordStep(trace.traceId(), 1, "MCP_WORK_ORDER_CREATE_FAILED", "MCP 本地工单创建失败");
            traceService.finish(trace.traceId(), "FAILED");
            throw exception;
        }
    }

    /** Backward-compatible Java entry point for callers that do not yet propagate an Agent trace. */
    public WorkOrder createAlertWorkOrder(String region, String groupName, String title, String severity, String description) {
        return createAlertWorkOrder(region, groupName, title, severity, description, null);
    }

    @McpTool(name = "list_open_work_orders", description = "List up to 20 open local simulated work orders for a region.")
    public List<WorkOrder> listOpenWorkOrders(
            @McpToolParam(required = true, description = "运营大区，例如华东") String region,
            @McpToolParam(required = true, description = "返回条数，1到20") Integer limit,
            @McpToolParam(required = false, description = "可选的 Agent 根 Trace ID；传入后将本次 MCP 调用关联为子 Trace") String correlationId) {
        regionAccessPolicy.assertAllowed(region);
        if (limit == null || limit < 1 || limit > 20) throw new IllegalArgumentException("limit 必须在 1 到 20 之间");
        var trace = traceService.start("MCP_WORK_ORDER_LIST", correlationId);
        long startedAt = System.nanoTime();
        try {
            List<WorkOrder> workOrders = jdbcTemplate.query("SELECT work_order_no, region_name, group_name, title, severity, status FROM analytics_mcp_work_order WHERE region_name = ? AND status = 'OPEN' ORDER BY created_at DESC LIMIT ?",
                    (row, ignored) -> new WorkOrder(row.getString("work_order_no"), row.getString("region_name"), row.getString("group_name"),
                            row.getString("title"), row.getString("severity"), row.getString("status"), null), region, limit)
                    .stream()
                    .map(order -> new WorkOrder(order.workOrderNo(), order.region(), order.groupName(), order.title(), order.severity(), order.status(), trace.traceId(), trace.correlationId()))
                    .toList();
            traceService.recordTool(trace.traceId(), 1, "list_open_work_orders", Map.of("region", region.trim(), "limit", limit),
                    elapsedMs(startedAt), "rows=" + workOrders.size());
            traceService.finish(trace.traceId(), "SUCCESS");
            return workOrders;
        } catch (RuntimeException exception) {
            traceService.recordStep(trace.traceId(), 1, "MCP_WORK_ORDER_LIST_FAILED", "MCP 本地工单查询失败");
            traceService.finish(trace.traceId(), "FAILED");
            throw exception;
        }
    }

    public List<WorkOrder> listOpenWorkOrders(String region, Integer limit) {
        return listOpenWorkOrders(region, limit, null);
    }

    @McpTool(name = "update_work_order_status", description = "Move a local simulated work order through the controlled lifecycle: OPEN to IN_PROGRESS or CLOSED, IN_PROGRESS to RESOLVED or CLOSED, and RESOLVED to CLOSED.")
    public WorkOrder updateWorkOrderStatus(
            @McpToolParam(required = true, description = "工单号") String workOrderNo,
            @McpToolParam(required = true, description = "目标状态：IN_PROGRESS、RESOLVED 或 CLOSED") String targetStatus,
            @McpToolParam(required = false, description = "可选的 Agent 根 Trace ID；传入后将本次 MCP 调用关联为子 Trace") String correlationId) {
        if (workOrderNo == null || workOrderNo.isBlank() || workOrderNo.length() > 64) {
            throw new IllegalArgumentException("workOrderNo 不能为空且最多64字符");
        }
        String normalizedStatus = targetStatus == null ? "" : targetStatus.trim().toUpperCase(Locale.ROOT);
        if (!TARGET_STATUSES.contains(normalizedStatus)) {
            throw new IllegalArgumentException("targetStatus 必须为 IN_PROGRESS、RESOLVED 或 CLOSED");
        }
        var trace = traceService.start("MCP_WORK_ORDER_STATUS_UPDATE", correlationId);
        long startedAt = System.nanoTime();
        try {
            WorkOrder existing = jdbcTemplate.queryForObject(
                    "SELECT work_order_no, region_name, group_name, title, severity, status FROM analytics_mcp_work_order WHERE work_order_no = ?",
                    (row, ignored) -> new WorkOrder(row.getString("work_order_no"), row.getString("region_name"), row.getString("group_name"),
                            row.getString("title"), row.getString("severity"), row.getString("status"), null), workOrderNo.trim());
            if (existing == null) throw new IllegalArgumentException("工单不存在");
            regionAccessPolicy.assertAllowed(existing.region());
            if (!isAllowedTransition(existing.status(), normalizedStatus)) {
                throw new IllegalArgumentException("不允许将 " + existing.status() + " 更新为 " + normalizedStatus);
            }
            jdbcTemplate.update("UPDATE analytics_mcp_work_order SET status = ? WHERE work_order_no = ?", normalizedStatus, existing.workOrderNo());
            traceService.recordTool(trace.traceId(), 1, "update_work_order_status",
                    Map.of("workOrderNo", existing.workOrderNo(), "targetStatus", normalizedStatus), elapsedMs(startedAt),
                    "from=" + existing.status() + ", to=" + normalizedStatus);
            traceService.finish(trace.traceId(), "SUCCESS");
            return new WorkOrder(existing.workOrderNo(), existing.region(), existing.groupName(), existing.title(), existing.severity(), normalizedStatus, trace.traceId(), trace.correlationId());
        } catch (RuntimeException exception) {
            traceService.recordStep(trace.traceId(), 1, "MCP_WORK_ORDER_STATUS_UPDATE_FAILED", "MCP 本地工单状态更新失败");
            traceService.finish(trace.traceId(), "FAILED");
            throw exception;
        }
    }

    public WorkOrder updateWorkOrderStatus(String workOrderNo, String targetStatus) {
        return updateWorkOrderStatus(workOrderNo, targetStatus, null);
    }

    private void validate(String title, String severity, String description) {
        if (title == null || title.isBlank() || title.length() > 160) throw new IllegalArgumentException("title 不能为空且最多160字符");
        if (description == null || description.isBlank() || description.length() > 2000) throw new IllegalArgumentException("description 不能为空且最多2000字符");
        if (severity == null || !SEVERITIES.contains(severity.toUpperCase(Locale.ROOT))) throw new IllegalArgumentException("severity 必须为 P1、P2、P3 或 P4");
    }

    private boolean isAllowedTransition(String currentStatus, String targetStatus) {
        return switch (currentStatus) {
            case "OPEN" -> targetStatus.equals("IN_PROGRESS") || targetStatus.equals("CLOSED");
            case "IN_PROGRESS" -> targetStatus.equals("RESOLVED") || targetStatus.equals("CLOSED");
            case "RESOLVED" -> targetStatus.equals("CLOSED");
            default -> false;
        };
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    public record WorkOrder(String workOrderNo, String region, String groupName, String title, String severity, String status,
                            String traceId, String correlationId) {
        public WorkOrder(String workOrderNo, String region, String groupName, String title, String severity, String status, String traceId) {
            this(workOrderNo, region, groupName, title, severity, status, traceId, null);
        }
    }
}
