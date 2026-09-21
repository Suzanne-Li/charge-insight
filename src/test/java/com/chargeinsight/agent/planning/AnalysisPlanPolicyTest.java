package com.chargeinsight.agent.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnalysisPlanPolicyTest {
    private final AnalysisPlanPolicy policy = new AnalysisPlanPolicy();

    @Test
    void routesCommunicationFaultRankingDeterministically() {
        AnalysisPlan plan = plan(AnalysisPlan.Intent.FAULT_ANALYSIS, List.of("communication_timeout"));

        AnalysisPlan normalized = policy.normalize("华东区域最近一周通信故障较多的五个桩群是什么？", plan);

        assertThat(normalized.intent()).isEqualTo(AnalysisPlan.Intent.RANKING);
    }

    @Test
    void routesDailyCityAggregationToControlledSqlFallback() {
        AnalysisPlan plan = plan(AnalysisPlan.Intent.METRIC, List.of("energy_kwh"));

        AnalysisPlan normalized = policy.normalize("按城市汇总华东区域最近一周的每日充电量", plan);

        assertThat(normalized.intent()).isEqualTo(AnalysisPlan.Intent.AD_HOC_QUERY);
    }

    @Test
    void routesNamedGroupCommunicationFaultQuestionToDomainFaultTool() {
        AnalysisPlan plan = new AnalysisPlan(AnalysisPlan.Intent.AD_HOC_QUERY, List.of("fault_pile_count"),
                new AnalysisPlan.Scope("华东", "上海", "私桩共享桩群1", "最近一周"),
                List.of("执行查询"), List.of("查询结果"));

        AnalysisPlan normalized = policy.normalize("华东上海私桩共享桩群1近7天通信故障明细", plan);

        assertThat(normalized.intent()).isEqualTo(AnalysisPlan.Intent.FAULT_ANALYSIS);
    }

    private AnalysisPlan plan(AnalysisPlan.Intent intent, List<String> metrics) {
        return new AnalysisPlan(intent, metrics,
                new AnalysisPlan.Scope("华东", "", "", "最近一周"), List.of("执行查询"), List.of("查询结果"));
    }
}
