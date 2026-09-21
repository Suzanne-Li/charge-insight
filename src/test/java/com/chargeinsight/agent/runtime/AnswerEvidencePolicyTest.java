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

    @Test
    void exposesSupplementalEvidenceAsCorrelationRatherThanCausation() {
        AnalyticsQueryTools.AnomalyEvidence evidence = new AnalyticsQueryTools.AnomalyEvidence(
                overview("2026-08-20", "2026-08-26", "894.80", "5.00", "0.3000", 36),
                overview("2026-08-13", "2026-08-19", "1587.20", "9.71", "0.0286", 65), List.of());
        List<AnalyticsQueryTools.GroupRanking> rankings = List.of(new AnalyticsQueryTools.GroupRanking(1, "上海私桩共享桩群1",
                AnalyticsQueryTools.GroupRankingMetric.OFFLINE_RATE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 10, new BigDecimal("0.30")));
        var execution = new AnalyticsToolRouter.ToolExecution(List.of("compareAnomalyEvidence", "rankGroups"),
                Map.of("periodComparison", evidence, "offlineGroupRanking", rankings), List.of());

        String answer = BusinessAnswerRenderer.render(plan(), execution);

        assertThat(answer).contains("补充核验", "上海私桩共享桩群1", "同期相关线索", "不构成直接因果证明");
    }

    private com.chargeinsight.agent.planning.AnalysisPlan plan() {
        return new com.chargeinsight.agent.planning.AnalysisPlan(
                com.chargeinsight.agent.planning.AnalysisPlan.Intent.ANOMALY_ROOT_CAUSE,
                List.of("gmv_amount"), new com.chargeinsight.agent.planning.AnalysisPlan.Scope(
                "华东", "上海", "上海私桩共享桩群1", "最近一周"), List.of(), List.of());
    }

    private AnalyticsQueryTools.OperationOverview overview(String start, String end, String gmv, String available, String offlineRate, long orders) {
        return new AnalyticsQueryTools.OperationOverview("华东", "上海私桩共享桩群1", LocalDate.parse(start), LocalDate.parse(end),
                LocalDate.parse(end), new BigDecimal(available).longValue(), 10, new BigDecimal(available), BigDecimal.ZERO,
                orders, orders, BigDecimal.ZERO, new BigDecimal(gmv), new BigDecimal(offlineRate), BigDecimal.ONE);
    }
}
