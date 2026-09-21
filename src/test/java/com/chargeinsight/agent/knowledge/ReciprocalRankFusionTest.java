package com.chargeinsight.agent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {
    @Test
    void promotesDocumentSupportedByBothIndependentRankings() {
        MetricKnowledgeService.Document lexicalOnly = document("lexical");
        MetricKnowledgeService.Document shared = document("shared");
        MetricKnowledgeService.Document denseOnly = document("dense");

        List<ReciprocalRankFusion.FusedDocument> fused = new ReciprocalRankFusion().fuse(
                List.of(lexicalOnly, shared),
                List.of(new DenseKnowledgeRetriever.RankedDocument(shared, 0.91),
                        new DenseKnowledgeRetriever.RankedDocument(denseOnly, 0.89)), 3);

        assertThat(fused).extracting(value -> value.document().id()).containsExactly("shared", "lexical", "dense");
    }

    private MetricKnowledgeService.Document document(String id) {
        return new MetricKnowledgeService.Document(id, "test", id, MetricKnowledgeService.KnowledgeType.METRIC);
    }
}
