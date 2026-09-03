package com.chargeinsight.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AnalyticsQueryPolicyTest {
    private final AnalyticsQueryPolicy policy = new AnalyticsQueryPolicy();

    @Test
    void acceptsInclusiveThirtyOneDayRangeAndDefaultLimit() {
        var range = policy.dateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertThat(range.endDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(policy.limit(null)).isEqualTo(10);
    }

    @Test
    void rejectsOversizedRangeAndLimit() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> policy.dateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1)));
        assertThatIllegalArgumentException().isThrownBy(() -> policy.limit(21));
    }
}
