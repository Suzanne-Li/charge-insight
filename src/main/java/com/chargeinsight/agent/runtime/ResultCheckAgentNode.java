package com.chargeinsight.agent.runtime;

import org.springframework.stereotype.Component;

@Component
public class ResultCheckAgentNode implements AnalyticsAgentNode {
    private final AnalyticsResultChecker checker;

    public ResultCheckAgentNode(AnalyticsResultChecker checker) { this.checker = checker; }
    @Override public String name() { return "RESULT_CHECK_AGENT"; }
    @Override public boolean supports(AnalyticsAgentContext context) {
        return context.execution() != null && context.executionComplete()
                && context.observation() != null && context.checkResult() == null;
    }

    @Override
    public NodeResult act(AnalyticsAgentContext context) {
        AnalyticsDataset dataset = AnalyticsDataset.from(context.planning().plan(), context.execution());
        context.dataset(dataset);
        var result = checker.check(context.planning().plan(), dataset, context.execution());
        context.checkResult(result);
        return new NodeResult("CHECK_QUERY_RESULT", "status=" + result.status() + ", rows=" + dataset.rows().size());
    }
}
