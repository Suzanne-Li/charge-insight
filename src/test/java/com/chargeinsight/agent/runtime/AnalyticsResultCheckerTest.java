package com.chargeinsight.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.chargeinsight.agent.planning.AnalysisPlan;
import com.chargeinsight.agent.tool.AnalysisPeriodResolver;
import com.chargeinsight.agent.tool.AnalyticsQueryPolicy;
import com.chargeinsight.agent.tool.AnalyticsToolRouter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalyticsResultCheckerTest {
    private final AnalyticsResultChecker checker = new AnalyticsResultChecker(
            new AnalysisPeriodResolver(null, new AnalyticsQueryPolicy()));

    @Test
    void acceptsDatasetWhenContractRegionAndDatesMatchPlan() {
        var result = checker.check(metricPlan(), metricDataset(new BigDecimal("0.9865")), execution("华东",
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 26)));

        assertThat(result.status()).isEqualTo("VALID");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void rejectsToolRegionOrDatesOutsidePlanScope() {
        var wrongRegion = checker.check(metricPlan(), metricDataset(new BigDecimal("0.9865")), execution("华南",
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 26)));
        var wrongDates = checker.check(metricPlan(), metricDataset(new BigDecimal("0.9865")), execution("华东",
                LocalDate.of(2026, 8, 19), LocalDate.of(2026, 8, 25)));

        assertThat(wrongRegion.status()).isEqualTo("INVALID");
        assertThat(wrongRegion.warnings()).containsExactly("工具查询区域与计划范围不一致");
        assertThat(wrongDates.status()).isEqualTo("INVALID");
        assertThat(wrongDates.warnings()).containsExactly("工具查询时间与计划范围不一致");
    }

    @Test
    void rejectsIntentDatasetMissingRequiredFields() {
        AnalysisPlan trendPlan = new AnalysisPlan(AnalysisPlan.Intent.TREND, List.of("gmv_amount"),
                metricPlan().scope(), List.of(), List.of());
        AnalyticsDataset dataset = new AnalyticsDataset("GMV 趋势",
                List.of(new AnalyticsDataset.Field("statDate", "日期", "DATE", "")),
                List.of(Map.of("statDate", LocalDate.of(2026, 8, 26))));

        var result = checker.check(trendPlan, dataset, execution("华东",
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 26)));

        assertThat(result.status()).isEqualTo("INVALID");
        assertThat(result.warnings()).containsExactly("Dataset 缺少必需字段：metricValue");
    }

    @Test
    void warnsWhenPercentageExceedsBusinessRange() {
        var result = checker.check(metricPlan(), metricDataset(new BigDecimal("1.2000")), execution("华东",
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 26)));

        assertThat(result.status()).isEqualTo("VALID_WITH_WARNINGS");
        assertThat(result.warnings()).containsExactly("共享成功率 超出 0%～100% 范围，请核对源数据或指标口径");
    }

    @Test
    void acceptsCommunicationFaultRankingContractForRankingIntent() {
        AnalysisPlan plan = new AnalysisPlan(AnalysisPlan.Intent.RANKING, List.of("communication_timeout"),
                metricPlan().scope(), List.of(), List.of());
        AnalyticsDataset dataset = new AnalyticsDataset("故障桩群排行", List.of(
                new AnalyticsDataset.Field("groupName", "桩群", "TEXT", ""),
                new AnalyticsDataset.Field("faultPileDays", "故障桩日", "NUMBER", "桩日"),
                new AnalyticsDataset.Field("faultDurationMinutes", "故障时长", "NUMBER", "分钟"),
                new AnalyticsDataset.Field("affectedOrderDays", "影响订单日", "NUMBER", "次")),
                List.of(Map.of("groupName", "上海私桩共享桩群1", "faultPileDays", 13,
                        "faultDurationMinutes", 3120, "affectedOrderDays", 6)));
        var audit = new AnalyticsToolRouter.ToolAudit("rankFaultGroups", Map.of(
                "region", "华东", "startDate", LocalDate.of(2026, 8, 20),
                "endDate", LocalDate.of(2026, 8, 26)), 1, "rows=1");

        var result = checker.check(plan, dataset,
                new AnalyticsToolRouter.ToolExecution(List.of("rankFaultGroups"), Map.of(), List.of(audit)));

        assertThat(result.status()).isEqualTo("VALID");
    }

    private AnalysisPlan metricPlan() {
        return new AnalysisPlan(AnalysisPlan.Intent.METRIC, List.of("gmv_amount"),
                new AnalysisPlan.Scope("华东", "", "", "2026-08-20 to 2026-08-26"), List.of(), List.of());
    }

    private AnalyticsDataset metricDataset(BigDecimal successRate) {
        List<AnalyticsDataset.Field> fields = List.of(
                new AnalyticsDataset.Field("snapshotDate", "数据日期", "DATE", ""),
                new AnalyticsDataset.Field("availablePileCount", "可用桩", "NUMBER", "个"),
                new AnalyticsDataset.Field("energyKwh", "累计充电量", "NUMBER", "kWh"),
                new AnalyticsDataset.Field("gmvAmount", "累计 GMV", "NUMBER", "元"),
                new AnalyticsDataset.Field("shareSuccessRate", "共享成功率", "PERCENT", "%"));
        return new AnalyticsDataset("华东区域运营总览", fields, List.of(Map.of(
                "snapshotDate", LocalDate.of(2026, 8, 26), "availablePileCount", 51,
                "energyKwh", new BigDecimal("5195.5"), "gmvAmount", new BigDecimal("9078.8"),
                "shareSuccessRate", successRate)));
    }

    private AnalyticsToolRouter.ToolExecution execution(String region, LocalDate start, LocalDate end) {
        Map<String, Object> parameters = Map.of("region", region, "startDate", start, "endDate", end);
        var audit = new AnalyticsToolRouter.ToolAudit("queryOperationOverview", parameters, 1, "resultType=OperationOverview");
        return new AnalyticsToolRouter.ToolExecution(List.of("queryOperationOverview"), Map.of(), List.of(audit));
    }
}
