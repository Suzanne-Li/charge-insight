package com.chargeinsight.agent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionKnowledgeRetrieverTest {
    @Test
    void fusesBm25AndDenseCandidatesWhenDenseIsAvailable() {
        MetricKnowledgeService.Document first = document("dense-one");
        MetricKnowledgeService.Document second = document("dense-two");
        DenseKnowledgeSearch dense = (query, topK) -> List.of(
                new DenseKnowledgeRetriever.RankedDocument(first, 0.9),
                new DenseKnowledgeRetriever.RankedDocument(second, 0.8));

        var result = new ProductionKnowledgeRetriever(new MetricKnowledgeService(), dense, new ReciprocalRankFusion())
                .retrieve("测试", 2);

        assertThat(result.strategy()).isEqualTo("BM25_DENSE_RRF");
        assertThat(result.documents()).hasSize(2);
        assertThat(result.audit().bm25CandidateIds()).isNotEmpty();
        assertThat(result.audit().denseCandidateIds()).containsExactly("dense-one", "dense-two");
        assertThat(result.audit().selectedDocumentIds()).hasSize(2);
    }

    @Test
    void fallsBackToBm25WhenDenseIsUnavailableOrIncomplete() {
        DenseKnowledgeSearch emptyDense = (query, topK) -> List.of();

        var result = new ProductionKnowledgeRetriever(new MetricKnowledgeService(), emptyDense, new ReciprocalRankFusion())
                .retrieve("GMV", 2);

        assertThat(result.strategy()).isEqualTo("BM25_FALLBACK");
        assertThat(result.documents()).hasSize(2);
        assertThat(result.audit().degradationReason()).isEqualTo("DENSE_CANDIDATES_INSUFFICIENT");
    }

    @Test
    void fallsBackToBm25WhenDenseSearchThrows() {
        DenseKnowledgeSearch unavailableDense = (query, topK) -> {
            throw new IllegalStateException("Qdrant unavailable");
        };

        var result = new ProductionKnowledgeRetriever(new MetricKnowledgeService(), unavailableDense,
                new ReciprocalRankFusion()).retrieve("GMV", 2);

        assertThat(result.strategy()).isEqualTo("BM25_FALLBACK");
        assertThat(result.audit().degradationReason()).isEqualTo("DENSE_UNAVAILABLE");
        assertThat(result.documents()).hasSize(2);
    }

    private MetricKnowledgeService.Document document(String id) {
        return new MetricKnowledgeService.Document(id, "test", id, MetricKnowledgeService.KnowledgeType.METRIC);
    }
}
