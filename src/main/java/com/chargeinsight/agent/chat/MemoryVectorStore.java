package com.chargeinsight.agent.chat;

import java.util.List;
import java.util.Map;

/** Derived semantic index for owner-scoped long-term memory records. */
public interface MemoryVectorStore {
    void upsert(List<VectorPoint> points, int dimensions);
    List<VectorMatch> search(String ownerUsername, float[] vector, int topK);
    void delete(String memoryId);

    record VectorPoint(String memoryId, float[] vector, Map<String, String> payload) { }
    record VectorMatch(String memoryId, double score) { }
}
