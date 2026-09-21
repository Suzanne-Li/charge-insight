package com.chargeinsight.agent.knowledge;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Explicit dense-index lifecycle for the static knowledge corpus. It deliberately does not run at
 * application startup: embedding calls may have a cost and require an operator to enable the feature.
 */
@Service
public class DenseKnowledgeRetriever implements DenseKnowledgeSearch {
    private final MetricKnowledgeService knowledgeService;
    private final EmbeddingGateway embeddingGateway;
    private final KnowledgeVectorStore vectorStore;
    private final boolean enabled;
    private final int defaultTopK;
    private final int embeddingBatchSize;

    public DenseKnowledgeRetriever(MetricKnowledgeService knowledgeService,
            EmbeddingGateway embeddingGateway, KnowledgeVectorStore vectorStore,
            @Value("${charge.vector.enabled:false}") boolean enabled,
            @Value("${charge.vector.qdrant.top-k:10}") int defaultTopK,
            @Value("${charge.vector.qdrant.embedding-batch-size:10}") int embeddingBatchSize) {
        this.knowledgeService = knowledgeService;
        this.embeddingGateway = embeddingGateway;
        this.vectorStore = vectorStore;
        this.enabled = enabled;
        this.defaultTopK = defaultTopK;
        this.embeddingBatchSize = embeddingBatchSize;
    }

    public IndexingResult rebuildIndex() {
        if (!enabled) return IndexingResult.disabled();
        List<MetricKnowledgeService.Document> documents = knowledgeService.documents();
        List<float[]> vectors = embedInBatches(documents.stream().map(MetricKnowledgeService.Document::content).toList());
        if (vectors.size() != documents.size() || vectors.isEmpty()) {
            throw new IllegalStateException("Embedding 返回数量与知识块数量不一致");
        }
        int dimensions = vectors.get(0).length;
        if (dimensions < 1 || vectors.stream().anyMatch(vector -> vector.length != dimensions)) {
            throw new IllegalStateException("Embedding 向量维度不一致");
        }
        List<KnowledgeVectorStore.VectorPoint> points = java.util.stream.IntStream.range(0, documents.size())
                .mapToObj(index -> point(documents.get(index), vectors.get(index))).toList();
        vectorStore.upsert(points, dimensions);
        return new IndexingResult(true, "INDEXED", documents.size(), dimensions, vectorStore.status().detail());
    }

    @Override
    public List<RankedDocument> retrieve(String query, int topK) {
        if (!enabled || query == null || query.isBlank()) return List.of();
        float[] vector = embeddingGateway.embed(MetricKnowledgeService.normalizeQuery(query));
        Map<String, MetricKnowledgeService.Document> documents = knowledgeService.documents().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(MetricKnowledgeService.Document::id, Function.identity()));
        return vectorStore.search(vector, topK > 0 ? topK : defaultTopK).stream()
                .map(match -> new RankedDocument(documents.get(match.id()), match.score()))
                .filter(result -> result.document() != null)
                .sorted(Comparator.comparingDouble(RankedDocument::score).reversed())
                .toList();
    }

    public KnowledgeVectorStore.VectorStoreStatus status() {
        return vectorStore.status();
    }

    private KnowledgeVectorStore.VectorPoint point(MetricKnowledgeService.Document document, float[] vector) {
        return new KnowledgeVectorStore.VectorPoint(document.id(), vector,
                Map.of("source", document.source(), "type", document.type().name()));
    }

    private List<float[]> embedInBatches(List<String> contents) {
        if (embeddingBatchSize < 1) throw new IllegalStateException("Embedding 批量大小必须大于 0");
        List<float[]> vectors = new ArrayList<>();
        for (int start = 0; start < contents.size(); start += embeddingBatchSize) {
            vectors.addAll(embeddingGateway.embed(contents.subList(start, Math.min(start + embeddingBatchSize, contents.size()))));
        }
        return vectors;
    }

    public record RankedDocument(MetricKnowledgeService.Document document, double score) { }
    public record IndexingResult(boolean enabled, String state, int indexedDocuments, int dimensions, String detail) {
        static IndexingResult disabled() { return new IndexingResult(false, "DISABLED", 0, 0, "CHARGE_VECTOR_ENABLED=false"); }
    }
}
