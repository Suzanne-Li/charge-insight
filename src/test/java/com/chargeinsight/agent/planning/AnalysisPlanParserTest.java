package com.chargeinsight.agent.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AnalysisPlanParserTest {
    private final AnalysisPlanParser parser = new AnalysisPlanParser(new ObjectMapper());

    @Test
    void parsesValidatedPlannerContract() {
        AnalysisPlan plan = parser.parse("""
                {"intent":"ANOMALY_ROOT_CAUSE","metrics":["gmv_amount"],"scope":{"region":"华东","group":"私桩共享桩群1","timeRange":"最近一周"},"steps":["对比本期和上期"],"evidenceNeeded":["GMV 周环比"]}
                """);

        assertThat(plan.intent()).isEqualTo(AnalysisPlan.Intent.ANOMALY_ROOT_CAUSE);
        assertThat(plan.scope().group()).isEqualTo("私桩共享桩群1");
    }

    @Test
    void acceptsRegionMetricWithoutGroupScope() {
        AnalysisPlan plan = parser.parse("""
                {"intent":"METRIC","metrics":["gmv_amount"],"scope":{"region":"华东","group":"","timeRange":"最近一周"},"steps":["查询"],"evidenceNeeded":["GMV"]}
                """);

        assertThat(plan.scope().group()).isBlank();
    }

    @Test
    void suppliesDeterministicWorkflowMetadataWhenModelLeavesItEmpty() {
        AnalysisPlan plan = parser.parse("""
                {"intent":"METRIC","metrics":["GMV"],"scope":{"region":"华东","group":"","timeRange":"最近一周"},"steps":[],"evidenceNeeded":[]}
                """);

        assertThat(plan.steps()).contains("查询运营总览");
        assertThat(plan.evidenceNeeded()).contains("周期指标汇总");
    }

    @Test
    void rejectsGroupRequiredTrendWithoutGroupScope() {
        assertThatThrownBy(() -> parser.parse("""
                {"intent":"TREND","metrics":["gmv_amount"],"scope":{"region":"华东","group":"","timeRange":"最近一周"},"steps":["查询"],"evidenceNeeded":["GMV"]}
                """))
                .isInstanceOf(AnalysisPlanParser.InvalidAnalysisPlanException.class)
                .hasMessageContaining("scope");
    }

    @Test
    void acceptsCrossRegionRankingWithoutRegionOrGroupScope() {
        AnalysisPlan plan = parser.parse("""
                {"intent":"RANKING","metrics":["offline_rate"],"scope":{"region":"","group":"","timeRange":"最近一周"},"steps":["排行"],"evidenceNeeded":["离线率"]}
                """);

        assertThat(plan.intent()).isEqualTo(AnalysisPlan.Intent.RANKING);
    }

    @Test
    void normalizesMetricAliasesToStableNames() {
        AnalysisPlan plan = parser.parse("""
                {"intent":"TREND","metrics":["GMV","交易额"],"scope":{"region":"华东","group":"私桩共享桩群1","timeRange":"最近一周"},"steps":["查询趋势"],"evidenceNeeded":["GMV"]}
                """);

        assertThat(plan.metrics()).containsExactly("gmv_amount");
    }
}
