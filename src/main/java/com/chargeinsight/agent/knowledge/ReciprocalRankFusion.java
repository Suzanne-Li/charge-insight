package com.chargeinsight.agent.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Deterministic reciprocal-rank fusion (RRF) for independently ranked lexical and dense results. */
@Component
public class ReciprocalRankFusion {
    private static final int RANK_CONSTANT = 60;

    public List<FusedDocument> fuse(List<MetricKnowledgeService.Document> lexical,
            List<DenseKnowledgeRetriever.RankedDocument> dense, int topK) {
        if (topK < 1) throw new IllegalArgumentException("topK 必须大于 0");
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        addLexical(lexical, candidates);
        addDense(dense, candidates);
        return candidates.values().stream().map(candidate -> new FusedDocument(candidate.document(), candidate.score()))
                .sorted(Comparator.comparingDouble(FusedDocument::score).reversed()
                        .thenComparing(value -> value.document().id()))
                .limit(topK).toList();
    }

    private void addLexical(List<MetricKnowledgeService.Document> results, Map<String, Candidate> candidates) {
        for (int index = 0; index < results.size(); index++) {
            MetricKnowledgeService.Document document = results.get(index);
            int rank = index + 1;
            candidates.compute(document.id(), (ignored, candidate) -> increment(candidate, document, rank));
        }
    }

    private void addDense(List<DenseKnowledgeRetriever.RankedDocument> results, Map<String, Candidate> candidates) {
        for (int index = 0; index < results.size(); index++) {
            MetricKnowledgeService.Document document = results.get(index).document();
            int rank = index + 1;
            candidates.compute(document.id(), (ignored, candidate) -> increment(candidate, document, rank));
        }
    }

    private Candidate increment(Candidate candidate, MetricKnowledgeService.Document document, int rank) {
        double addition = 1.0 / (RANK_CONSTANT + rank);
        return candidate == null ? new Candidate(document, addition) : new Candidate(document, candidate.score() + addition);
    }

    public record FusedDocument(MetricKnowledgeService.Document document, double score) { }
    private record Candidate(MetricKnowledgeService.Document document, double score) { }
}
