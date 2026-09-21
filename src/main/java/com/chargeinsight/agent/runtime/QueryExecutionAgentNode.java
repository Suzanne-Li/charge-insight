package com.chargeinsight.agent.runtime;

import com.chargeinsight.agent.planning.AnalysisPlan;
import com.chargeinsight.agent.sql.TextToSqlFallbackService;
import com.chargeinsight.agent.tool.AnalyticsToolRouter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class QueryExecutionAgentNode implements AnalyticsAgentNode {
    private final AnalyticsToolRouter toolRouter;
    private final TextToSqlFallbackService textToSqlFallbackService;
    private final AnalyticsToolSchemaRegistry schemas;
    private final RootCauseEvidencePolicy rootCauseEvidencePolicy;

    public QueryExecutionAgentNode(AnalyticsToolRouter toolRouter, TextToSqlFallbackService textToSqlFallbackService,
                                   AnalyticsToolSchemaRegistry schemas, RootCauseEvidencePolicy rootCauseEvidencePolicy) {
        this.toolRouter = toolRouter;
        this.textToSqlFallbackService = textToSqlFallbackService;
        this.schemas = schemas;
        this.rootCauseEvidencePolicy = rootCauseEvidencePolicy;
    }
    @Override public String name() { return "QUERY_EXECUTION_AGENT"; }
    @Override public boolean supports(AnalyticsAgentContext context) {
        return context.planning() != null && context.planning().plan() != null && context.nextAction() != null
                && !context.executionComplete();
    }

    @Override
    public NodeResult act(AnalyticsAgentContext context) {
        AnalysisPlan plan = context.planning().plan();
        schemas.validate(context.nextAction(), plan);
        var stepExecution = context.nextAction().action() == AgentAction.EXECUTE_SQL_FALLBACK
                ? executeFallback(context, plan)
                : plan.intent() == AnalysisPlan.Intent.ANOMALY_ROOT_CAUSE
                    ? toolRouter.executeAnomalyStep(plan, context.nextAction(), previousEvidence(context), context.toolAuditConsumer())
                    : toolRouter.execute(plan, context.toolAuditConsumer());
        var execution = merge(context.execution(), stepExecution);
        context.execution(execution);
        AgentObservation observation = new AgentObservation(context.nextAction().action(), true, stepExecution.invokedTools(),
                stepExecution.toolAudits().stream().mapToInt(audit -> rowsFromSummary(audit.resultSummary())).sum(),
                "tools=" + stepExecution.invokedTools() + ", calls=" + stepExecution.toolAudits().size());
        context.observation(observation);
        return transitionAfterObservation(context, plan, stepExecution, execution);
    }

    private NodeResult transitionAfterObservation(AnalyticsAgentContext context, AnalysisPlan plan,
                                                  AnalyticsToolRouter.ToolExecution stepExecution,
                                                  AnalyticsToolRouter.ToolExecution combinedExecution) {
        if (plan.intent() != AnalysisPlan.Intent.ANOMALY_ROOT_CAUSE) {
            context.markExecutionComplete();
            return new NodeResult(context.nextAction().action().name(), "tools=" + stepExecution.invokedTools()
                    + ", calls=" + stepExecution.toolAudits().size());
        }
        if (!context.rootCauseObservationHandled()) {
            context.markRootCauseObservationHandled();
            Object comparison = combinedExecution.evidence().get("periodComparison");
            if (comparison instanceof com.chargeinsight.agent.tool.AnalyticsQueryTools.AnomalyEvidence evidence) {
                var next = rootCauseEvidencePolicy.selectFollowUp(evidence);
                if (next.isPresent()) {
                    schemas.validate(next.get(), plan);
                    context.nextAction(next.get());
                    return new NodeResult(context.observation().action().name(), "tools=" + stepExecution.invokedTools()
                            + "; observation=" + next.get().rationaleSummary()
                            + "; next=" + next.get().allowedTools());
                }
            }
        }
        context.nextAction(null);
        context.markExecutionComplete();
        return new NodeResult(context.observation().action().name(), "tools=" + stepExecution.invokedTools()
                + "; observation=现有同期证据足以进入结果校验");
    }

    private Map<String, Object> previousEvidence(AnalyticsAgentContext context) {
        return context.execution() == null ? Map.of() : context.execution().evidence();
    }

    private AnalyticsToolRouter.ToolExecution merge(AnalyticsToolRouter.ToolExecution previous,
                                                     AnalyticsToolRouter.ToolExecution current) {
        if (previous == null) return current;
        Map<String, Object> evidence = new LinkedHashMap<>(previous.evidence());
        evidence.putAll(current.evidence());
        List<String> tools = java.util.stream.Stream.concat(previous.invokedTools().stream(), current.invokedTools().stream())
                .distinct().toList();
        List<AnalyticsToolRouter.ToolAudit> audits = java.util.stream.Stream.concat(previous.toolAudits().stream(), current.toolAudits().stream()).toList();
        return new AnalyticsToolRouter.ToolExecution(tools, Map.copyOf(evidence), audits);
    }

    private int rowsFromSummary(String summary) {
        if (summary == null || !summary.startsWith("rows=")) return 0;
        int delimiter = summary.indexOf(',');
        String raw = delimiter < 0 ? summary.substring(5) : summary.substring(5, delimiter);
        try { return Integer.parseInt(raw); } catch (NumberFormatException ignored) { return 0; }
    }

    private AnalyticsToolRouter.ToolExecution executeFallback(AnalyticsAgentContext context, AnalysisPlan plan) {
        long startedAt = System.nanoTime();
        TextToSqlFallbackService.FallbackExecution result = textToSqlFallbackService.execute(context.question(), plan);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("sql", result.sql());
        parameters.put("views", result.views());
        parameters.put("region", result.region());
        parameters.put("startDate", result.startDate());
        parameters.put("endDate", result.endDate());
        parameters.put("attempts", result.attempts());
        AnalyticsToolRouter.ToolAudit audit = new AnalyticsToolRouter.ToolAudit("controlledTextToSql", Map.copyOf(parameters),
                durationMs, "rows=" + result.rows().size() + ", estimatedRows=" + result.estimatedRows());
        context.toolAuditConsumer().accept(audit);
        return new AnalyticsToolRouter.ToolExecution(List.of("controlledTextToSql"),
                Map.of("controlledSql", result), List.of(audit));
    }
}
