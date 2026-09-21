package com.chargeinsight.agent.knowledge;

import java.util.List;
import java.util.Map;

/**
 * Derived vector index for knowledge chunks. The markdown corpus remains the source of truth;
 * an implementation must be safe to rebuild from {@link MetricKnowledgeService#documents()}.
 */
public interface KnowledgeVectorStore {
    void upsert(List<VectorPoint> points, int dimensions);

    List<VectorMatch> search(float[] vector, int topK);

    VectorStoreStatus status();

    record VectorPoint(String id, float[] vector, Map<String, String> payload) { }
    record VectorMatch(String id, double score) { }
    record VectorStoreStatus(boolean enabled, String state, String detail) { }
}
