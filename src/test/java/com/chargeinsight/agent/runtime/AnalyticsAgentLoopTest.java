package com.chargeinsight.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AnalyticsAgentLoopTest {
    @Test
    void executesSupportedNodesInStateOrderAndStopsWhenFinished() {
        AtomicInteger state = new AtomicInteger();
        AnalyticsAgentNode first = node("FIRST", 0, state, false);
        AnalyticsAgentNode second = node("SECOND", 1, state, false);
        AnalyticsAgentNode third = node("THIRD", 2, state, true);
        AnalyticsAgentLoop loop = new AnalyticsAgentLoop(List.of(first, second, third));
        AnalyticsAgentContext context = new AnalyticsAgentContext("trace", "question", Instant.now(), ignored -> { });

        loop.run(context, ignored -> { });

        assertThat(context.finished()).isTrue();
        assertThat(context.steps()).extracting(AnalyticsAgentContext.AgentLoopStep::node)
                .containsExactly("FIRST", "SECOND", "THIRD");
    }

    @Test
    void rejectsNonTerminatingWorkflowAfterExactlyEightSteps() {
        AtomicInteger executions = new AtomicInteger();
        AnalyticsAgentNode nonTerminating = new AnalyticsAgentNode() {
            @Override public String name() { return "LOOP"; }
            @Override public boolean supports(AnalyticsAgentContext context) { return true; }
            @Override public NodeResult act(AnalyticsAgentContext context) {
                executions.incrementAndGet();
                return new NodeResult("RETRY", "still running");
            }
        };
        AnalyticsAgentLoop loop = new AnalyticsAgentLoop(List.of(nonTerminating));
        AnalyticsAgentContext context = new AnalyticsAgentContext("trace", "question", Instant.now(), ignored -> { });

        assertThatIllegalStateException().isThrownBy(() -> loop.run(context, ignored -> { }))
                .withMessage("问数 Agent 超过最大步骤数 8");

        assertThat(executions).hasValue(8);
        assertThat(context.steps()).hasSize(8);
    }

    private AnalyticsAgentNode node(String name, int supportedState, AtomicInteger state, boolean finish) {
        return new AnalyticsAgentNode() {
            @Override public String name() { return name; }
            @Override public boolean supports(AnalyticsAgentContext context) { return state.get() == supportedState; }
            @Override public NodeResult act(AnalyticsAgentContext context) {
                state.incrementAndGet();
                if (finish) context.finish();
                return new NodeResult("ACT", "state=" + state.get());
            }
        };
    }
}
