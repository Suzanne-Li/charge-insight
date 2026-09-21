package com.chargeinsight.agent.api;

import com.chargeinsight.agent.evaluation.RetrievalEvaluationService;
import com.chargeinsight.agent.evaluation.RetrievalEvaluationService.EvaluationSplit;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Privileged endpoint for a same-set BM25, Dense and RRF comparison; it never changes production routing. */
@RestController
@RequestMapping("/api/admin/knowledge-index/evaluation")
public class KnowledgeRetrievalEvaluationController {
    private final RetrievalEvaluationService evaluation;

    public KnowledgeRetrievalEvaluationController(RetrievalEvaluationService evaluation) { this.evaluation = evaluation; }

    @GetMapping
    public Map<String, Object> evaluate(@RequestParam(defaultValue = "HOLDOUT") EvaluationSplit split) {
        var lexical = evaluation.run(split).strategies().get(com.chargeinsight.agent.knowledge.MetricKnowledgeService.RetrievalStrategy.BM25);
        return Map.of("status", "SUCCESS", "split", split, "result", Map.of(
                "BM25", lexical,
                "DENSE", evaluation.runDense(split).metrics(),
                "BM25_DENSE_RRF", evaluation.runRrf(split).metrics()));
    }

    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam(defaultValue = "COLLOQUIAL_HOLDOUT") EvaluationSplit split) {
        return Map.of("status", "SUCCESS", "result", evaluation.audit(split));
    }
}
