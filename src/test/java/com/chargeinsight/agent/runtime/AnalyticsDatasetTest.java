package com.chargeinsight.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.chargeinsight.agent.planning.AnalysisPlan;
import com.chargeinsight.agent.tool.AnalyticsQueryTools;
import com.chargeinsight.agent.tool.AnalyticsToolRouter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalyticsDatasetTest {

    @Test
    void prefersPeriodComparisonOverSupportingTrendsForRootCauseDataset() {
        var plan = new AnalysisPlan(AnalysisPlan.Intent.ANOMALY_ROOT_CAUSE, List.of("gmv_amount"),
                new AnalysisPlan.Scope("华东", "上海", "私桩共享桩群1", "最近一周"), List.of(), List.of());
        var current = overview(new BigDecimal("800.00"));
        var previous = overview(new BigDecimal("1000.00"));
        var evidence = new AnalyticsQueryTools.AnomalyEvidence(current, previous, List.of());
        var points = List.of(new AnalyticsQueryTools.TrendPoint(LocalDate.of(2026, 8, 26),
                AnalyticsQueryTools.TrendMetric.GMV_AMOUNT, new BigDecimal("128.50")));
        var execution = new AnalyticsToolRouter.ToolExecution(List.of("compareAnomalyEvidence", "queryOperationTrend"),
                Map.of("periodComparison", evidence, "gmvTrend", points), List.of());

        AnalyticsDataset dataset = AnalyticsDataset.from(plan, execution);

        assertThat(dataset.title()).isEqualTo("周期对比");
        assertThat(dataset.fields()).extracting(AnalyticsDataset.Field::key)
                .containsExactly("period", "gmvAmount", "availablePileCount", "orderCount", "offlineRate");
        assertThat(dataset.rows()).extracting(row -> row.get("period")).containsExactly("本期", "上期");
        assertThat(dataset.rows()).extracting(row -> row.get("gmvAmount"))
                .containsExactly(new BigDecimal("800.00"), new BigDecimal("1000.00"));
    }

    @Test
    void mapsTrendEvidenceToStableDataset() {
        var plan = new AnalysisPlan(AnalysisPlan.Intent.TREND, List.of("gmv_amount"),
                new AnalysisPlan.Scope("华东", "上海", "私桩共享桩群1", "最近一周"), List.of(), List.of());
        var points = List.of(new AnalyticsQueryTools.TrendPoint(LocalDate.of(2026, 8, 26),
                AnalyticsQueryTools.TrendMetric.GMV_AMOUNT, new BigDecimal("128.50")));
        var execution = new AnalyticsToolRouter.ToolExecution(List.of("queryOperationTrend"),
                Map.of("gmvTrend", points), List.of());

        AnalyticsDataset dataset = AnalyticsDataset.from(plan, execution);

        assertThat(dataset.title()).isEqualTo("GMV 趋势");
        assertThat(dataset.fields()).extracting(AnalyticsDataset.Field::key)
                .containsExactly("statDate", "metricValue");
        assertThat(dataset.rows()).singleElement().satisfies(row -> {
            assertThat(row.get("statDate")).isEqualTo(LocalDate.of(2026, 8, 26));
            assertThat(row.get("metricValue")).isEqualTo(new BigDecimal("128.50"));
        });
    }

    @Test
    void labelsNonGmvTrendUsingItsActualMetric() {
        var plan = new AnalysisPlan(AnalysisPlan.Intent.TREND, List.of("energy_kwh"),
                new AnalysisPlan.Scope("华东", "上海", "私桩共享桩群1", "最近一周"), List.of(), List.of());
        var points = List.of(new AnalyticsQueryTools.TrendPoint(LocalDate.of(2026, 8, 26),
                AnalyticsQueryTools.TrendMetric.ENERGY_KWH, new BigDecimal("128.50")));
        var execution = new AnalyticsToolRouter.ToolExecution(List.of("queryOperationTrend"),
                Map.of("trend", points), List.of());

        AnalyticsDataset dataset = AnalyticsDataset.from(plan, execution);

        assertThat(dataset.title()).isEqualTo("充电量 趋势");
        assertThat(dataset.fields().get(1)).extracting(AnalyticsDataset.Field::label, AnalyticsDataset.Field::unit)
                .containsExactly("充电量", "kWh");
    }

    private AnalyticsQueryTools.OperationOverview overview(BigDecimal gmvAmount) {
        return new AnalyticsQueryTools.OperationOverview("华东", "私桩共享桩群1",
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 26), LocalDate.of(2026, 8, 26),
                10, 12, new BigDecimal("9.50"), new BigDecimal("1.00"), 80, 79,
                new BigDecimal("500.00"), gmvAmount, new BigDecimal("0.0833"), new BigDecimal("0.9875"));
    }
}
