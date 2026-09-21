package com.chargeinsight.agent.runtime;

/** Actions allowed by the bounded analytics runtime. No model output can execute an action outside this enum. */
public enum AgentAction {
    EXECUTE_DOMAIN_TOOLS,
    EXECUTE_SQL_FALLBACK,
    ANSWER,
    REQUEST_CLARIFICATION,
    FAIL
}
