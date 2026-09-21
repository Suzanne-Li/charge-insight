package com.chargeinsight.agent.runtime;

import org.springframework.stereotype.Component;

/** Selects the only runtime action that a validated plan is permitted to execute. */
@Component
public class NextActionAgentNode implements AnalyticsAgentNode {
    private final NextActionPolicy policy;

    public NextActionAgentNode(NextActionPolicy policy) { this.policy = policy; }
    @Override public String name() { return "NEXT_ACTION_AGENT"; }
    @Override public boolean supports(AnalyticsAgentContext context) {
        return context.planning() != null && context.planning().plan() != null && context.nextAction() == null
                && context.execution() == null;
    }

    @Override
    public NodeResult act(AnalyticsAgentContext context) {
        AgentDecision decision = policy.decide(context.planning().plan());
        context.nextAction(decision);
        return new NodeResult(decision.action().name(), "tools=" + decision.allowedTools()
                + ", rationale=" + decision.rationaleSummary());
    }
}
