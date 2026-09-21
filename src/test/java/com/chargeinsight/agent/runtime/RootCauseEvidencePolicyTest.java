package com.chargeinsight.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.chargeinsight.agent.tool.AnalyticsQueryTools;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RootCauseEvidencePolicyTest {
    private final RootCauseEvidencePolicy policy = new RootCauseEvidencePolicy();

    @Test
    void prioritizesAffectedGroupLocationWhenOfflineRateWorsens() {
        var decision = policy.selectFollowUp(evidence("0.12", 12, "0.08", 15, "100", "90", 80, 100)).orElseThrow();

        assertThat(decision.allowedTools()).containsExactly("rankGroups");
        assertThat(decision.expectedEvidence()).containsExactly("离线率桩群排行");
    }

    @Test
    void checksAvailabilityTrendWhenSupplyDropsWithoutOfflineRateIncrease() {
        var decision = policy.selectFollowUp(evidence("0.08", 10, "0.08", 15, "90", "100", 90, 100)).orElseThrow();

        assertThat(decision.allowedTools()).containsExactly("queryOperationTrend");
        assertThat(decision.expectedEvidence()).containsExactly("可用桩趋势");
    }

    @Test
    void skipsUnneededFollowUpWhenNoPrimaryMetricWorsens() {
        assertThat(policy.selectFollowUp(evidence("0.08", 15, "0.08", 15, "110", "100", 110, 100))).isEmpty();
    }

    private AnalyticsQueryTools.AnomalyEvidence evidence(String currentOffline, long currentAvailable,
                                                          String previousOffline, long previousAvailable,
                                                          String currentGmv, String previousGmv,
                                                          long currentOrders, long previousOrders) {
        return new AnalyticsQueryTools.AnomalyEvidence(
                overview(currentOffline, currentAvailable, currentGmv, currentOrders),
                overview(previousOffline, previousAvailable, previousGmv, previousOrders), List.of());
    }

    private AnalyticsQueryTools.OperationOverview overview(String offlineRate, long available, String gmv, long orders) {
        return new AnalyticsQueryTools.OperationOverview("华东", "测试桩群", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7),
                LocalDate.of(2026, 9, 7), available, available, BigDecimal.valueOf(available), BigDecimal.ZERO,
                orders, orders, BigDecimal.ZERO, new BigDecimal(gmv), new BigDecimal(offlineRate), BigDecimal.ONE);
    }
}
