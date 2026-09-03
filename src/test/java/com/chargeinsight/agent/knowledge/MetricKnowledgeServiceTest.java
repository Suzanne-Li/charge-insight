package com.chargeinsight.agent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class MetricKnowledgeServiceTest {
    private final MetricKnowledgeService knowledgeService = new MetricKnowledgeService();

    @Test
    void retrievesAnomalyPlaybookForGmvAndCommunicationFaultQuestion() {
        List<MetricKnowledgeService.Document> documents = knowledgeService.retrieve("GMV下降和通信故障如何归因", 2);

        assertThat(documents).isNotEmpty();
        assertThat(documents.get(0).source()).startsWith("agent-knowledge/anomaly-playbook.md");
    }

    @Test
    void rejectsInvalidTopK() {
        assertThatIllegalArgumentException().isThrownBy(() -> knowledgeService.retrieve("GMV", 0));
    }

    @Test
    void retrievesSchemaAndSqlExampleByKnowledgeType() {
        assertThat(knowledgeService.retrieve("通信故障字段", MetricKnowledgeService.KnowledgeType.SCHEMA, 1))
                .extracting(MetricKnowledgeService.Document::source).allMatch(source -> source.contains("schema.md"));
        assertThat(knowledgeService.retrieve("华东通信故障桩群排行", MetricKnowledgeService.KnowledgeType.SQL_EXAMPLE, 1))
                .first().extracting(MetricKnowledgeService.Document::content).asString().contains("rankFaultGroups");
    }
}
