package com.chargeinsight.agent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DenseKnowledgeRetrieverTest {
    @Test
    void rebuildsDerivedIndexFromAllKnowledgeChunksOnlyWhenEnabled() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        DenseKnowledgeRetriever retriever = new DenseKnowledgeRetriever(new MetricKnowledgeService(), new FakeEmbeddingGateway(),
                store, true, 10, 10);

        DenseKnowledgeRetriever.IndexingResult result = retriever.rebuildIndex();

        assertThat(result).extracting("state", "indexedDocuments", "dimensions")
                .containsExactly("INDEXED", 60, 3);
        assertThat(store.points).hasSize(60);
        assertThat(store.points.get(0).payload()).containsKeys("source", "type");
    }

    @Test
    void returnsNoDenseResultWhenVectorFeatureIsDisabled() {
        DenseKnowledgeRetriever retriever = new DenseKnowledgeRetriever(new MetricKnowledgeService(), new FakeEmbeddingGateway(),
                new InMemoryVectorStore(), false, 10, 10);

        assertThat(retriever.rebuildIndex()).extracting("state", "indexedDocuments").containsExactly("DISABLED", 0);
        assertThat(retriever.retrieve("华东 GMV", 5)).isEmpty();
    }

    @Test
    void mapsVectorMatchesBackToKnowledgeDocumentsInScoreOrder() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.matches = List.of(
                new KnowledgeVectorStore.VectorMatch("rule.gmv-period-comparison", 0.81),
                new KnowledgeVectorStore.VectorMatch("metric.gmv-amount", 0.96));
        DenseKnowledgeRetriever retriever = new DenseKnowledgeRetriever(new MetricKnowledgeService(), new FakeEmbeddingGateway(),
                store, true, 10, 10);

        assertThat(retriever.retrieve("GMV 异常", 5)).extracting(value -> value.document().id())
                .containsExactly("metric.gmv-amount", "rule.gmv-period-comparison");
    }

    private static final class FakeEmbeddingGateway implements EmbeddingGateway {
        @Override public List<float[]> embed(List<String> texts) {
            List<float[]> vectors = new ArrayList<>();
            for (int index = 0; index < texts.size(); index++) vectors.add(new float[] {index, 1F, 2F});
            return vectors;
        }
        @Override public float[] embed(String text) { return new float[] {1F, 2F, 3F}; }
    }

    private static final class InMemoryVectorStore implements KnowledgeVectorStore {
        private List<VectorPoint> points = List.of();
        private List<VectorMatch> matches = List.of();
        @Override public void upsert(List<VectorPoint> points, int dimensions) { this.points = List.copyOf(points); }
        @Override public List<VectorMatch> search(float[] vector, int topK) { return matches; }
        @Override public VectorStoreStatus status() { return new VectorStoreStatus(true, "CONFIGURED", "test"); }
    }
}
