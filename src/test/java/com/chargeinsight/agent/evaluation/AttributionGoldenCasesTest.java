package com.chargeinsight.agent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AttributionGoldenCasesTest {
    @Test
    void loadsVersionedAttributionHarnessCases() throws Exception {
        try (var input = new ClassPathResource("agent-evaluation/attribution-golden-cases.json").getInputStream()) {
            List<GoldenCase> cases = new ObjectMapper().readValue(input, new TypeReference<>() { });

            assertThat(cases).hasSize(5).allSatisfy(value -> {
                assertThat(value.expectedIntent()).isEqualTo("ANOMALY_ROOT_CAUSE");
                assertThat(value.requiredTools()).startsWith("compareAnomalyEvidence");
                assertThat(value.maxToolCalls()).isBetween(1, 2);
                assertThat(value.expectedNodeSequence()).startsWith("PLANNING_AGENT", "NEXT_ACTION_AGENT", "QUERY_EXECUTION_AGENT");
            });
            assertThat(cases).filteredOn(value -> value.expectedFaultCode() == null).hasSize(2);
            assertThat(cases).filteredOn(value -> value.expectedFaultCode() != null).hasSize(3);
        }
    }

    record GoldenCase(String id, String question, String expectedIntent, List<String> requiredTools,
                      List<String> expectedNodeSequence, int maxToolCalls, List<String> requiredAnswerPhrases,
                      List<String> forbiddenAnswerPhrases,
                      PeriodEvidence current, PeriodEvidence previous, String expectedFaultCode,
                      Long expectedFaultDurationMinutes) { }
    record PeriodEvidence(double gmvAmount, long orderCount, long availablePileCount, double offlineRate) { }
}
