package com.chargeinsight.agent.runtime;

import java.util.List;

/** Safe result summary returned to the bounded loop after an action. */
public record AgentObservation(
        AgentAction action,
        boolean successful,
        List<String> invokedTools,
        int rowCount,
        String summary) {

    public AgentObservation {
        invokedTools = List.copyOf(invokedTools);
    }
}
