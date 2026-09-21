package com.chargeinsight.agent.runtime;

import org.springframework.stereotype.Component;

@Component
public class OperationsAnswerAgentNode implements AnalyticsAgentNode {
    @Override public String name() { return "OPERATIONS_ANSWER_AGENT"; }
    @Override public boolean supports(AnalyticsAgentContext context) {
        return context.checkResult() != null && context.completionAction() != null && context.answer() == null;
    }

    @Override
    public NodeResult act(AnalyticsAgentContext context) {
        if (context.completionAction().action() == AgentAction.ANSWER) {
            context.answer(BusinessAnswerRenderer.render(context.planning().plan(), context.execution()));
        } else {
            context.answer(String.join("；", context.checkResult().warnings()) + "。请调整时间或区域范围后重试。");
        }
        context.finish();
        return new NodeResult("GENERATE_OPERATIONS_ANSWER", "answerLength=" + context.answer().length());
    }
}
