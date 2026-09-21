package com.chargeinsight.agent.api;

import com.chargeinsight.agent.knowledge.DenseKnowledgeRetriever;
import com.chargeinsight.agent.knowledge.MixedKnowledgeRetriever;
import com.chargeinsight.agent.knowledge.MetricKnowledgeService;
import com.chargeinsight.agent.runtime.AnalyticsPlanningService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Privileged, evaluation-only comparison; it does not alter the production Dense-first retriever. */
@RestController
@RequestMapping("/api/admin/planner-retrieval-comparison")
public class PlannerRetrievalComparisonController {
    private final AnalyticsPlanningService planning;
    private final DenseKnowledgeRetriever dense;
    private final MixedKnowledgeRetriever rrf;

    public PlannerRetrievalComparisonController(AnalyticsPlanningService planning, DenseKnowledgeRetriever dense,
                                                MixedKnowledgeRetriever rrf) {
        this.planning = planning;
        this.dense = dense;
        this.rrf = rrf;
    }

    @PostMapping
    public Map<String, Object> compare(@Valid @RequestBody CompareRequest request) {
        return Map.of("status", "SUCCESS", "dense", plan(request.question(), Strategy.DENSE),
                "rrf", plan(request.question(), Strategy.RRF));
    }

    private PlanComparison plan(String question, Strategy strategy) {
        long started = System.nanoTime();
        List<MetricKnowledgeService.Document> documents = switch (strategy) {
            case DENSE -> dense.retrieve(question, 4).stream().map(DenseKnowledgeRetriever.RankedDocument::document).toList();
            case RRF -> rrf.retrieve(question, 4).stream().map(value -> value.document()).toList();
        };
        AnalyticsPlanningService.PlanningOutcome outcome = planning.planWithDocuments(question, documents);
        return new PlanComparison(strategy.name(), outcome.status(), outcome.plan(), outcome.knowledgeSources(),
                (System.nanoTime() - started) / 1_000_000);
    }

    public record CompareRequest(@NotBlank String question) { }
    public record PlanComparison(String strategy, String status, Object plan, List<String> knowledgeSources, long durationMs) { }
    private enum Strategy { DENSE, RRF }
}
