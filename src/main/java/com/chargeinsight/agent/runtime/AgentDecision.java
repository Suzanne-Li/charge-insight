package com.chargeinsight.agent.runtime;

import java.util.List;

/**
 * Auditable next-action decision derived from a validated plan and constrained by the tool schema.
 * rationaleSummary is deliberately concise and is not model chain-of-thought.
 */
public record AgentDecision(
        AgentAction action,
        List<String> allowedTools,
        String rationaleSummary,
        List<String> expectedEvidence) {

    public AgentDecision {
        allowedTools = List.copyOf(allowedTools);
        expectedEvidence = List.copyOf(expectedEvidence);
    }
}
