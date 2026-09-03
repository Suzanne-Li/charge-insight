package com.chargeinsight.agent.api;

import com.chargeinsight.agent.evaluation.AgentEvaluationService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/evaluations")
public class AgentEvaluationController {
    private final AgentEvaluationService evaluationService;

    public AgentEvaluationController(AgentEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @GetMapping("/cases")
    public Map<String, Object> cases() {
        return Map.of("status", "SUCCESS", "cases", evaluationService.cases());
    }

    @PostMapping("/run")
    public Map<String, Object> run() {
        AgentEvaluationService.EvaluationReport report = evaluationService.run();
        return Map.of("status", report.failed() == 0 ? "SUCCESS" : "FAILED", "report", report);
    }
}
