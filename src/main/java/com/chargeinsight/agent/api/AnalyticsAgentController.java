package com.chargeinsight.agent.api;

import com.chargeinsight.agent.runtime.AnalyticsAgentRuntime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class AnalyticsAgentController {
    private final AnalyticsAgentRuntime runtime;
    public AnalyticsAgentController(AnalyticsAgentRuntime runtime) { this.runtime = runtime; }

    @PostMapping("/plan")
    public Map<String, Object> plan(@Valid @RequestBody PlanRequest request) {
        AnalyticsAgentRuntime.AgentPlan plan = runtime.plan(request.question());
        return Map.of("status", plan.status(), "result", plan);
    }

    @PostMapping("/analyze")
    public Map<String, Object> analyze(@Valid @RequestBody AnalyzeRequest request) {
        AnalyticsAgentRuntime.AnalysisResult result = runtime.analyze(request.question(), request.sessionId(), ignored -> { });
        return Map.of("status", result.status(), "result", result);
    }

    public record PlanRequest(@NotBlank String question) { }
    public record AnalyzeRequest(@NotBlank String question, String sessionId) { }
}
