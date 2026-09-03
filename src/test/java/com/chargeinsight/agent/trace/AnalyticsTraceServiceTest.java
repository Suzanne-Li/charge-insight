package com.chargeinsight.agent.trace;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class AnalyticsTraceServiceTest {
    private final AnalyticsTraceService traceService = new AnalyticsTraceService(null);

    @Test
    void rejectsOutOfRangeListLimitsBeforeDatabaseAccess() {
        assertThatIllegalArgumentException().isThrownBy(() -> traceService.list(0))
                .withMessage("limit 必须在 1 到 100 之间");
        assertThatIllegalArgumentException().isThrownBy(() -> traceService.list(101))
                .withMessage("limit 必须在 1 到 100 之间");
    }

    @Test
    void rejectsMalformedTraceIdBeforeDatabaseAccess() {
        assertThatIllegalArgumentException().isThrownBy(() -> traceService.get("not-a-trace"))
                .withMessage("traceId 格式无效");
    }
}
