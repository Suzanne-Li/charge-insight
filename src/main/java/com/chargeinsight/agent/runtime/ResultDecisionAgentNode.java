package com.chargeinsight.agent.runtime;

import org.springframework.stereotype.Component;

/** Converts a checked observation into a bounded terminal decision. */
@Component
public class ResultDecisionAgentNode implements AnalyticsAgentNode {
    private final NextActionPolicy policy;

    public ResultDecisionAgentNode(NextActionPolicy policy) { this.policy = policy; }
    @Override public String name() { return "RESULT_DECISION_AGENT"; }
    @Override public boolean supports(AnalyticsAgentContext context) {
        return context.checkResult() != null && context.completionAction() == null;
    }

    @Override
    public NodeResult act(AnalyticsAgentContext context) {
        AgentDecision decision = policy.decideAfterObservation(context.checkResult());
        context.completionAction(decision);
        return new NodeResult(decision.action().name(), decision.rationaleSummary());
    }
}
