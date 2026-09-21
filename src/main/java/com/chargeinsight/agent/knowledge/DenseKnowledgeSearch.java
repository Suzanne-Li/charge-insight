package com.chargeinsight.agent.knowledge;

import java.util.List;

/** Read-side port for dense knowledge retrieval. */
public interface DenseKnowledgeSearch {
    List<DenseKnowledgeRetriever.RankedDocument> retrieve(String query, int topK);
}
