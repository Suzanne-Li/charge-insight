package com.chargeinsight.agent.knowledge;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Production BM25 + dense retrieval with rank-based fusion and a safe BM25-only degradation path. */
@Service
public class ProductionKnowledgeRetriever {
    private static final Logger log = LoggerFactory.getLogger(ProductionKnowledgeRetriever.class);
    private final MetricKnowledgeService lexical;
    private final DenseKnowledgeSearch dense;
    private final ReciprocalRankFusion rrf;

    public ProductionKnowledgeRetriever(MetricKnowledgeService lexical, DenseKnowledgeSearch dense,
                                        ReciprocalRankFusion rrf) {
        this.lexical = lexical;
        this.dense = dense;
        this.rrf = rrf;
    }

    public RetrievalResult retrieve(String query, int topK) {
        int candidateK = Math.max(topK * 3, 10);
        List<MetricKnowledgeService.Document> bm25 = lexical.retrieve(query, candidateK,
                MetricKnowledgeService.RetrievalStrategy.BM25);
        try {
            List<DenseKnowledgeRetriever.RankedDocument> denseCandidates = dense.retrieve(query, candidateK);
            if (denseCandidates.size() < topK) {
                log.warn("Dense knowledge retrieval returned {} of {} required results; falling back to BM25", denseCandidates.size(), topK);
                return fallback(bm25, topK, "DENSE_CANDIDATES_INSUFFICIENT", List.of());
            }
            List<ReciprocalRankFusion.FusedDocument> fused = rrf.fuse(bm25, denseCandidates, topK);
            if (fused.size() < topK) {
                log.warn("RRF produced {} of {} required results; falling back to BM25", fused.size(), topK);
                return fallback(bm25, topK, "RRF_RESULTS_INSUFFICIENT", denseIds(denseCandidates));
            }
            return new RetrievalResult(fused.stream().map(ReciprocalRankFusion.FusedDocument::document).toList(),
                    "BM25_DENSE_RRF", new RetrievalAudit("BM25_DENSE_RRF", ids(bm25), denseIds(denseCandidates),
                    fused.stream().map(value -> value.document().id()).toList(), ""));
        } catch (RuntimeException exception) {
            log.warn("Dense knowledge retrieval unavailable; falling back to BM25: {}", exception.getMessage());
            return fallback(bm25, topK, "DENSE_UNAVAILABLE", List.of());
        }
    }

    private RetrievalResult fallback(List<MetricKnowledgeService.Document> bm25, int topK, String reason,
                                     List<String> denseCandidateIds) {
        List<MetricKnowledgeService.Document> documents = bm25.stream().limit(topK).toList();
        return new RetrievalResult(documents, "BM25_FALLBACK", new RetrievalAudit("BM25_FALLBACK", ids(bm25),
                denseCandidateIds, ids(documents), reason));
    }

    private List<String> ids(List<MetricKnowledgeService.Document> documents) {
        return documents.stream().map(MetricKnowledgeService.Document::id).toList();
    }

    private List<String> denseIds(List<DenseKnowledgeRetriever.RankedDocument> documents) {
        return documents.stream().map(value -> value.document().id()).toList();
    }

    public record RetrievalResult(List<MetricKnowledgeService.Document> documents, String strategy,
                                  RetrievalAudit audit) { }
    public record RetrievalAudit(String strategy, List<String> bm25CandidateIds, List<String> denseCandidateIds,
                                 List<String> selectedDocumentIds, String degradationReason) {
        public String summary() {
            return "strategy=" + strategy + ", bm25Candidates=" + bm25CandidateIds.size()
                    + ", denseCandidates=" + denseCandidateIds.size() + ", selected=" + selectedDocumentIds
                    + (degradationReason.isBlank() ? "" : ", degradation=" + degradationReason);
        }
    }
}
