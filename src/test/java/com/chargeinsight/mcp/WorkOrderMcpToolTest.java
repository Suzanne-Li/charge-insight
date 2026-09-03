package com.chargeinsight.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chargeinsight.security.RegionAccessPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class WorkOrderMcpToolTest {
    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final WorkOrderMcpTool tool = new WorkOrderMcpTool(
            jdbcTemplate, org.mockito.Mockito.mock(com.chargeinsight.agent.tool.GroupEntityResolver.class), new RegionAccessPolicy());

    @Test
    void listsOpenWorkOrdersWithTraceIdAndAuditSteps() {
        WorkOrderMcpTool.WorkOrder workOrder = new WorkOrderMcpTool.WorkOrder(
                "WO-1", "华东", "上海私桩共享桩群1", "通信故障", "P2", "OPEN", null);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("华东"), eq(10))).thenReturn(List.of(workOrder));

        List<WorkOrderMcpTool.WorkOrder> results = tool.listOpenWorkOrders("华东", 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).traceId()).isNotBlank();
        verify(jdbcTemplate, times(3)).update(anyString(), any(Object[].class));
    }

    @Test
    void updatesWorkOrderThroughAllowedLifecycleWithTrace() {
        WorkOrderMcpTool.WorkOrder openOrder = new WorkOrderMcpTool.WorkOrder(
                "WO-1", "华东", "上海私桩共享桩群1", "通信故障", "P2", "OPEN", null);
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), eq("WO-1"))).thenReturn(openOrder);

        WorkOrderMcpTool.WorkOrder updated = tool.updateWorkOrderStatus("WO-1", "in_progress");

        assertThat(updated.status()).isEqualTo("IN_PROGRESS");
        assertThat(updated.traceId()).isNotBlank();
        verify(jdbcTemplate, times(4)).update(anyString(), any(Object[].class));
    }

    @Test
    void refusesLifecycleTransitionThatSkipsInvestigation() {
        WorkOrderMcpTool.WorkOrder openOrder = new WorkOrderMcpTool.WorkOrder(
                "WO-1", "华东", "上海私桩共享桩群1", "通信故障", "P2", "OPEN", null);
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), eq("WO-1"))).thenReturn(openOrder);

        assertThatThrownBy(() -> tool.updateWorkOrderStatus("WO-1", "RESOLVED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许");
    }
}
