package com.chargeinsight.agent.runtime;

import java.util.List;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** MindBridge-style bounded state loop adapted to analytics query execution. */
@Service
public class AnalyticsAgentLoop {
    private static final int MAX_STEPS = 8;
    private final List<AnalyticsAgentNode> nodes;

    @Autowired
    public AnalyticsAgentLoop(PlanningAgentNode planning, NextActionAgentNode nextAction,
                              QueryExecutionAgentNode execution, ResultCheckAgentNode check,
                              ResultDecisionAgentNode resultDecision, OperationsAnswerAgentNode answer) {
        this.nodes = List.of(planning, nextAction, execution, check, resultDecision, answer);
    }

    AnalyticsAgentLoop(List<AnalyticsAgentNode> nodes) {
        this.nodes = List.copyOf(nodes);
    }

    public AnalyticsAgentContext run(AnalyticsAgentContext context,
                                     Consumer<AnalyticsAgentContext.AgentLoopStep> stepConsumer) {
        for (int number = 1; number <= MAX_STEPS && !context.finished(); number++) {
            AnalyticsAgentNode node = nodes.stream().filter(candidate -> candidate.supports(context)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("没有可处理当前问数状态的 Agent 节点"));
            long startedAt = System.nanoTime();
            AnalyticsAgentNode.NodeResult result = node.act(context);
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            var step = new AnalyticsAgentContext.AgentLoopStep(number, node.name(), result.action(),
                    result.summary(), durationMs);
            context.addStep(step);
            stepConsumer.accept(step);
        }
        if (!context.finished()) throw new IllegalStateException("问数 Agent 超过最大步骤数 " + MAX_STEPS);
        return context;
    }
}
