package com.chargeinsight.agent.runtime;

import org.springframework.stereotype.Component;

@Component
public class PlanningAgentNode implements AnalyticsAgentNode {
    private final AnalyticsPlanningService planningService;

    public PlanningAgentNode(AnalyticsPlanningService planningService) { this.planningService = planningService; }
    @Override public String name() { return "PLANNING_AGENT"; }
    @Override public boolean supports(AnalyticsAgentContext context) { return context.planning() == null; }

    @Override
    public NodeResult act(AnalyticsAgentContext context) {
        var outcome = planningService.plan(context.question());
        context.planning(outcome);
        if (!"SUCCESS".equals(outcome.status())) context.finish();
        return new NodeResult("CREATE_QUERY_PLAN", "status=" + outcome.status()
                + ", knowledgeSources=" + outcome.knowledgeSources().size());
    }
}
