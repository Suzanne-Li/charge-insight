package com.chargeinsight.agent.planning;

import java.util.List;

/** Validated contract between the LLM planner and downstream domain tools. */
public record AnalysisPlan(
        Intent intent,
        List<String> metrics,
        Scope scope,
        List<String> steps,
        List<String> evidenceNeeded) {

    public enum Intent {
        METRIC, RANKING, TREND, ANOMALY_ROOT_CAUSE, FAULT_ANALYSIS, AD_HOC_QUERY
    }

    public record Scope(String region, String city, String group, String timeRange) { }
}
