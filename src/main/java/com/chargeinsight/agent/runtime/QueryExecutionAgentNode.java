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

    public QueryExecutionAgentNode(AnalyticsToolRouter toolRouter, TextToSqlFallbackService textToSqlFallbackService) {
        this.toolRouter = toolRouter;
        this.textToSqlFallbackService = textToSqlFallbackService;
    }
    @Override public String name() { return "QUERY_EXECUTION_AGENT"; }
    @Override public boolean supports(AnalyticsAgentContext context) {
        return context.planning() != null && context.planning().plan() != null && context.execution() == null;
    }

    @Override
    public NodeResult act(AnalyticsAgentContext context) {
        AnalysisPlan plan = context.planning().plan();
        var execution = plan.intent() == AnalysisPlan.Intent.AD_HOC_QUERY
                ? executeFallback(context, plan)
                : toolRouter.execute(plan, context.toolAuditConsumer());
        context.execution(execution);
        return new NodeResult(plan.intent() == AnalysisPlan.Intent.AD_HOC_QUERY ? "EXECUTE_CONTROLLED_SQL_FALLBACK" : "EXECUTE_DOMAIN_TOOLS",
                "tools=" + execution.invokedTools()
                + ", calls=" + execution.toolAudits().size());
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
