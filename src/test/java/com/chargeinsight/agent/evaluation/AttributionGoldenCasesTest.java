package com.chargeinsight.agent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AttributionGoldenCasesTest {
    @Test
    void loadsFourDiverseAttributionGoldenCases() throws Exception {
        try (var input = new ClassPathResource("agent-evaluation/attribution-golden-cases.json").getInputStream()) {
            List<GoldenCase> cases = new ObjectMapper().readValue(input, new TypeReference<>() { });

            assertThat(cases).hasSize(4).allSatisfy(value -> {
                assertThat(value.expectedIntent()).isEqualTo("ANOMALY_ROOT_CAUSE");
                assertThat(value.requiredTools()).contains("compareAnomalyEvidence", "queryOperationTrend", "rankGroups");
                assertThat(value.current().gmvAmount()).isLessThan(value.previous().gmvAmount());
            });
            assertThat(cases).filteredOn(value -> value.expectedFaultCode() == null).hasSize(1);
            assertThat(cases).filteredOn(value -> value.expectedFaultCode() != null).hasSize(3);
        }
    }

    record GoldenCase(String id, String question, String expectedIntent, List<String> requiredTools,
                      PeriodEvidence current, PeriodEvidence previous, String expectedFaultCode,
                      Integer expectedFaultDurationMinutes) { }
    record PeriodEvidence(double gmvAmount, long orderCount, long availablePileCount, double offlineRate) { }
}
