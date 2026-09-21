package com.chargeinsight.agent.knowledge;

import java.util.List;
import org.springframework.stereotype.Service;

/** BM25 + dense candidate retrieval fused by rank; score scales are never combined directly. */
@Service
public class MixedKnowledgeRetriever {
    private final MetricKnowledgeService lexical;
    private final DenseKnowledgeSearch dense;
    private final ReciprocalRankFusion rrf;

    public MixedKnowledgeRetriever(MetricKnowledgeService lexical, DenseKnowledgeSearch dense, ReciprocalRankFusion rrf) {
        this.lexical = lexical;
        this.dense = dense;
        this.rrf = rrf;
    }

    public List<ReciprocalRankFusion.FusedDocument> retrieve(String query, int topK) {
        int candidateK = Math.max(topK * 3, 10);
        return rrf.fuse(lexical.retrieve(query, candidateK, MetricKnowledgeService.RetrievalStrategy.BM25),
                dense.retrieve(query, candidateK), topK);
    }
}
