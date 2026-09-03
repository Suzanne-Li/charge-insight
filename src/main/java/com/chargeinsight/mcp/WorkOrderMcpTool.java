package com.chargeinsight.mcp;

import com.chargeinsight.agent.tool.GroupEntityResolver;
import com.chargeinsight.security.RegionAccessPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
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

    public WorkOrderMcpTool(JdbcTemplate jdbcTemplate, GroupEntityResolver groupEntityResolver, RegionAccessPolicy regionAccessPolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.groupEntityResolver = groupEntityResolver;
        this.regionAccessPolicy = regionAccessPolicy;
    }

    @McpTool(name = "create_alert_work_order", description = "Create a local simulated work order for an analytics alert. The caller must have the requested region scope.")
    public WorkOrder createAlertWorkOrder(
            @McpToolParam(required = true, description = "运营大区，例如华东") String region,
            @McpToolParam(required = true, description = "桩群名称") String groupName,
            @McpToolParam(required = true, description = "工单标题，最多160字符") String title,
            @McpToolParam(required = true, description = "优先级：P1、P2、P3、P4") String severity,
            @McpToolParam(required = true, description = "告警或处置说明，最多2000字符") String description) {
        validate(title, severity, description);
        regionAccessPolicy.assertAllowed(region);
        String traceId = startTrace("MCP_WORK_ORDER_CREATE");
        try {
            GroupEntityResolver.ResolvedGroup group = groupEntityResolver.resolve(region, "", groupName);
            String workOrderNo = "WO-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
            jdbcTemplate.update("INSERT INTO analytics_mcp_work_order(work_order_no, region_name, group_id, group_name, title, description, severity, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN')",
                    workOrderNo, group.region(), group.matchedGroup().groupId(), group.matchedGroup().groupName(), title.trim(), description.trim(), severity.toUpperCase(Locale.ROOT));
            saveTrace(traceId, "MCP_WORK_ORDER_CREATE", "workOrderNo=" + workOrderNo + ", severity=" + severity.toUpperCase(Locale.ROOT));
            finishTrace(traceId, "SUCCESS");
            return new WorkOrder(workOrderNo, group.region(), group.matchedGroup().groupName(), title.trim(), severity.toUpperCase(Locale.ROOT), "OPEN", traceId);
        } catch (RuntimeException exception) {
            saveTrace(traceId, "MCP_WORK_ORDER_CREATE_FAILED", "MCP 本地工单创建失败");
            finishTrace(traceId, "FAILED");
            throw exception;
        }
    }

    @McpTool(name = "list_open_work_orders", description = "List up to 20 open local simulated work orders for a region.")
    public List<WorkOrder> listOpenWorkOrders(
            @McpToolParam(required = true, description = "运营大区，例如华东") String region,
            @McpToolParam(required = true, description = "返回条数，1到20") Integer limit) {
        regionAccessPolicy.assertAllowed(region);
        if (limit == null || limit < 1 || limit > 20) throw new IllegalArgumentException("limit 必须在 1 到 20 之间");
        String traceId = startTrace("MCP_WORK_ORDER_LIST");
        try {
            List<WorkOrder> workOrders = jdbcTemplate.query("SELECT work_order_no, region_name, group_name, title, severity, status FROM analytics_mcp_work_order WHERE region_name = ? AND status = 'OPEN' ORDER BY created_at DESC LIMIT ?",
                    (row, ignored) -> new WorkOrder(row.getString("work_order_no"), row.getString("region_name"), row.getString("group_name"),
                            row.getString("title"), row.getString("severity"), row.getString("status"), null), region, limit)
                    .stream()
                    .map(order -> new WorkOrder(order.workOrderNo(), order.region(), order.groupName(), order.title(), order.severity(), order.status(), traceId))
                    .toList();
            saveTrace(traceId, "MCP_WORK_ORDER_LIST", "region=" + region.trim() + ", rows=" + workOrders.size());
            finishTrace(traceId, "SUCCESS");
            return workOrders;
        } catch (RuntimeException exception) {
            saveTrace(traceId, "MCP_WORK_ORDER_LIST_FAILED", "MCP 本地工单查询失败");
            finishTrace(traceId, "FAILED");
            throw exception;
        }
    }

    @McpTool(name = "update_work_order_status", description = "Move a local simulated work order through the controlled lifecycle: OPEN to IN_PROGRESS or CLOSED, IN_PROGRESS to RESOLVED or CLOSED, and RESOLVED to CLOSED.")
    public WorkOrder updateWorkOrderStatus(
            @McpToolParam(required = true, description = "工单号") String workOrderNo,
            @McpToolParam(required = true, description = "目标状态：IN_PROGRESS、RESOLVED 或 CLOSED") String targetStatus) {
        if (workOrderNo == null || workOrderNo.isBlank() || workOrderNo.length() > 64) {
            throw new IllegalArgumentException("workOrderNo 不能为空且最多64字符");
        }
        String normalizedStatus = targetStatus == null ? "" : targetStatus.trim().toUpperCase(Locale.ROOT);
        if (!TARGET_STATUSES.contains(normalizedStatus)) {
            throw new IllegalArgumentException("targetStatus 必须为 IN_PROGRESS、RESOLVED 或 CLOSED");
        }
        String traceId = startTrace("MCP_WORK_ORDER_STATUS_UPDATE");
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
            saveTrace(traceId, "MCP_WORK_ORDER_STATUS_UPDATE", "workOrderNo=" + existing.workOrderNo() + ", from=" + existing.status() + ", to=" + normalizedStatus);
            finishTrace(traceId, "SUCCESS");
            return new WorkOrder(existing.workOrderNo(), existing.region(), existing.groupName(), existing.title(), existing.severity(), normalizedStatus, traceId);
        } catch (RuntimeException exception) {
            saveTrace(traceId, "MCP_WORK_ORDER_STATUS_UPDATE_FAILED", "MCP 本地工单状态更新失败");
            finishTrace(traceId, "FAILED");
            throw exception;
        }
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

    private String startTrace(String question) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("INSERT INTO analytics_agent_trace(trace_id, question, status, started_at) VALUES (?, ?, 'RUNNING', NOW())", traceId, question);
        return traceId;
    }

    private void saveTrace(String traceId, String type, String summary) {
        jdbcTemplate.update("INSERT INTO analytics_agent_trace_step(trace_id, step_number, step_type, summary) VALUES (?, 1, ?, ?)", traceId, type, summary);
    }

    private void finishTrace(String traceId, String status) {
        jdbcTemplate.update("UPDATE analytics_agent_trace SET status=?, completed_at=NOW() WHERE trace_id=?", status, traceId);
    }

    public record WorkOrder(String workOrderNo, String region, String groupName, String title, String severity, String status, String traceId) { }
}
