package com.chargeinsight.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.chargeinsight.agent.tool.AnalyticsQueryTools;
import com.chargeinsight.agent.tool.AnalyticsToolRouter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnswerEvidencePolicyTest {
    @Test
    void rendersBusinessConclusionAndKeepsCausalBoundary() {
        AnalyticsQueryTools.OperationOverview current = overview("2026-08-20", "2026-08-26", "894.80", "5.00", "0.3000", 36);
        AnalyticsQueryTools.OperationOverview previous = overview("2026-08-13", "2026-08-19", "1587.20", "9.71", "0.0286", 65);
        AnalyticsQueryTools.AnomalyEvidence evidence = new AnalyticsQueryTools.AnomalyEvidence(current, previous,
                List.of(new AnalyticsQueryTools.FaultBreakdown("通信故障", "COMMUNICATION_TIMEOUT", 13, 3120, 6)));
        var execution = new AnalyticsToolRouter.ToolExecution(List.of("compareAnomalyEvidence"), Map.of("periodComparison", evidence), List.of());

        var plan = new com.chargeinsight.agent.planning.AnalysisPlan(
                com.chargeinsight.agent.planning.AnalysisPlan.Intent.ANOMALY_ROOT_CAUSE,
                List.of("gmv_amount"), new com.chargeinsight.agent.planning.AnalysisPlan.Scope(
                        "华东", "上海", "上海私桩共享桩群1", "最近一周"), List.of(), List.of());
        String answer = BusinessAnswerRenderer.render(plan, execution);

        assertThat(answer).contains("894.80", "1587.20", "可能原因", "直接因果关系");
        assertThat(answer).doesNotContain("受控证据", "observations", "toolExecution");
        assertThat(answer).doesNotContain("充分条件", "根本原因", "需求被");
    }

    private AnalyticsQueryTools.OperationOverview overview(String start, String end, String gmv, String available, String offlineRate, long orders) {
        return new AnalyticsQueryTools.OperationOverview("华东", "上海私桩共享桩群1", LocalDate.parse(start), LocalDate.parse(end),
                LocalDate.parse(end), new BigDecimal(available).longValue(), 10, new BigDecimal(available), BigDecimal.ZERO,
                orders, orders, BigDecimal.ZERO, new BigDecimal(gmv), new BigDecimal(offlineRate), BigDecimal.ONE);
    }
}
