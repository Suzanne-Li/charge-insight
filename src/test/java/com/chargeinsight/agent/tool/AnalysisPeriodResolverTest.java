package com.chargeinsight.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AnalysisPeriodResolverTest {
    private final AnalysisPeriodResolver resolver = new AnalysisPeriodResolver(new JdbcTemplate(), new AnalyticsQueryPolicy());

    @Test
    void parsesExplicitIsoDateRangeAndBuildsPreviousPeriod() {
        var current = resolver.resolve("2026-08-20 至 2026-08-26");
        var previous = resolver.previousPeriod(current);

        assertThat(current.startDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(previous.startDate()).isEqualTo(LocalDate.of(2026, 8, 13));
        assertThat(previous.endDate()).isEqualTo(LocalDate.of(2026, 8, 19));
    }

    @Test
    void parsesPlannerCanonicalCommaSeparatedDateRange() {
        var period = resolver.resolve("2026-08-20,2026-08-26");

        assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 8, 26));
        assertThat(period.source()).isEqualTo("EXPLICIT_DATE_RANGE");
    }

    @Test
    void rejectsUnboundedNaturalLanguageRange() {
        assertThatIllegalArgumentException().isThrownBy(() -> resolver.resolve("今年以来"));
    }

    @Test
    void resolvesPlannerCanonicalLastSevenDaysValue() {
        AnalysisPeriodResolver canonicalResolver = new AnalysisPeriodResolver(new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType) {
                return requiredType.cast(LocalDate.of(2026, 8, 26));
            }
        }, new AnalyticsQueryPolicy());

        var period = canonicalResolver.resolve("last_7_days");

        assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 8, 26));
        assertThat(period.source()).isEqualTo("LATEST_7_DAYS");
    }
}
