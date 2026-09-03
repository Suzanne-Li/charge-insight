package com.chargeinsight.agent.metric;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MetricCatalogServiceTest {
    private final MetricCatalogService catalog = new MetricCatalogService();

    @Test
    void distinguishesPeriodEndAvailablePilesFromDailyAverage() {
        var available = catalog.require("可用桩数");
        var average = catalog.require("日均可用桩");

        assertThat(available.kind()).isEqualTo(MetricCatalogService.MetricKind.SNAPSHOT);
        assertThat(available.aggregation()).isEqualTo("PERIOD_END");
        assertThat(average.kind()).isEqualTo(MetricCatalogService.MetricKind.AVERAGE);
        assertThat(average.aggregation()).isEqualTo("DAILY_AVERAGE");
    }
}
