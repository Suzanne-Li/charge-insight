package com.chargeinsight.agent.runtime;

import com.chargeinsight.agent.tool.AnalyticsToolRouter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Mutable state for one bounded analytics Agent run. */
public final class AnalyticsAgentContext {
    private final String traceId;
    private final String question;
    private final Instant startedAt;
    private final Consumer<AnalyticsToolRouter.ToolAudit> toolAuditConsumer;
    private final List<AgentLoopStep> steps = new ArrayList<>();
    private AnalyticsPlanningService.PlanningOutcome planning;
    private AnalyticsToolRouter.ToolExecution execution;
    private AnalyticsDataset dataset;
    private AnalyticsResultChecker.CheckResult checkResult;
    private String answer;
    private boolean finished;

    public AnalyticsAgentContext(String traceId, String question, Instant startedAt,
                                 Consumer<AnalyticsToolRouter.ToolAudit> toolAuditConsumer) {
        this.traceId = traceId;
        this.question = question;
        this.startedAt = startedAt;
        this.toolAuditConsumer = toolAuditConsumer;
    }

    public String traceId() { return traceId; }
    public String question() { return question; }
    public Instant startedAt() { return startedAt; }
    public Consumer<AnalyticsToolRouter.ToolAudit> toolAuditConsumer() { return toolAuditConsumer; }
    public List<AgentLoopStep> steps() { return List.copyOf(steps); }
    public void addStep(AgentLoopStep step) { steps.add(step); }
    public AnalyticsPlanningService.PlanningOutcome planning() { return planning; }
    public void planning(AnalyticsPlanningService.PlanningOutcome planning) { this.planning = planning; }
    public AnalyticsToolRouter.ToolExecution execution() { return execution; }
    public void execution(AnalyticsToolRouter.ToolExecution execution) { this.execution = execution; }
    public AnalyticsDataset dataset() { return dataset; }
    public void dataset(AnalyticsDataset dataset) { this.dataset = dataset; }
    public AnalyticsResultChecker.CheckResult checkResult() { return checkResult; }
    public void checkResult(AnalyticsResultChecker.CheckResult checkResult) { this.checkResult = checkResult; }
    public String answer() { return answer; }
    public void answer(String answer) { this.answer = answer; }
    public boolean finished() { return finished; }
    public void finish() { this.finished = true; }

    public record AgentLoopStep(int number, String node, String action, String summary, long durationMs) { }
}
