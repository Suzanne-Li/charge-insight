package com.chargeinsight.agent.runtime;

/** One deterministic state transition in the bounded analytics Agent loop. */
public interface AnalyticsAgentNode {
    String name();
    boolean supports(AnalyticsAgentContext context);
    NodeResult act(AnalyticsAgentContext context);

    record NodeResult(String action, String summary) { }
}
