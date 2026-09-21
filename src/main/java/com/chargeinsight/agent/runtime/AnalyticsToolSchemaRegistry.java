package com.chargeinsight.agent.runtime;

import com.chargeinsight.agent.planning.AnalysisPlan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Runtime-owned schemas that translate a validated intent into the bounded tool surface.
 * The schema is intentionally small: it prevents a plan from naming arbitrary tools.
 */
@Component
public class AnalyticsToolSchemaRegistry {
    private final Map<AnalysisPlan.Intent, ToolSchema> schemas = new LinkedHashMap<>();

    public AnalyticsToolSchemaRegistry() {
        register(AnalysisPlan.Intent.METRIC, List.of("queryOperationOverview"), "运营总览", List.of("指标结果"));
        register(AnalysisPlan.Intent.TREND, List.of("queryOperationTrend"), "趋势查询", List.of("时间序列"));
        register(AnalysisPlan.Intent.RANKING, List.of("rankGroups", "rankFaultGroups"), "桩群排行", List.of("排行结果"));
        register(AnalysisPlan.Intent.FAULT_ANALYSIS, List.of("queryFaultBreakdown", "rankFaultGroups"), "故障分析", List.of("故障证据"));
        register(AnalysisPlan.Intent.ANOMALY_ROOT_CAUSE,
                List.of("compareAnomalyEvidence", "queryOperationTrend", "rankGroups"), "异常归因", List.of("同期对比", "趋势", "排行"));
        register(AnalysisPlan.Intent.AD_HOC_QUERY, List.of("controlledTextToSql"), "受限动态查询", List.of("受控 SQL 结果"));
    }

    public ToolSchema schemaFor(AnalysisPlan.Intent intent) {
        ToolSchema schema = schemas.get(intent);
        if (schema == null) throw new IllegalArgumentException("未定义意图的工具 Schema：" + intent);
        return schema;
    }

    public void validate(AgentDecision decision, AnalysisPlan plan) {
        ToolSchema schema = schemaFor(plan.intent());
        if (!schema.tools().containsAll(decision.allowedTools())) {
            throw new IllegalArgumentException("NextAction 包含当前意图未允许的工具");
        }
        AgentAction expected = plan.intent() == AnalysisPlan.Intent.AD_HOC_QUERY
                ? AgentAction.EXECUTE_SQL_FALLBACK : AgentAction.EXECUTE_DOMAIN_TOOLS;
        if (decision.action() != expected) {
            throw new IllegalArgumentException("NextAction 与已校验意图不一致");
        }
    }

    private void register(AnalysisPlan.Intent intent, List<String> tools, String purpose, List<String> evidence) {
        schemas.put(intent, new ToolSchema(intent, List.copyOf(tools), purpose, List.copyOf(evidence)));
    }

    public record ToolSchema(AnalysisPlan.Intent intent, List<String> tools, String purpose, List<String> evidence) {
        public ToolSchema {
            tools = List.copyOf(tools);
            evidence = List.copyOf(evidence);
        }
    }
}
