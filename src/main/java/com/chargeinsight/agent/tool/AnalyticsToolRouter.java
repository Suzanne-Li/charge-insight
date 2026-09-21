package com.chargeinsight.agent.tool;

import com.chargeinsight.agent.planning.AnalysisPlan;
import com.chargeinsight.agent.runtime.AgentDecision;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Deterministic router from a validated plan to bounded domain tools. */
@Service
public class AnalyticsToolRouter {
    private final AnalyticsQueryTools queryTools;
    private final AnalysisPeriodResolver periodResolver;
    private final GroupEntityResolver groupEntityResolver;

    public AnalyticsToolRouter(
            AnalyticsQueryTools queryTools,
            AnalysisPeriodResolver periodResolver,
            GroupEntityResolver groupEntityResolver) {
        this.queryTools = queryTools;
        this.periodResolver = periodResolver;
        this.groupEntityResolver = groupEntityResolver;
    }

    public ToolExecution execute(AnalysisPlan plan) {
        return execute(plan, ignored -> { });
    }

    /** Executes one bounded tool at a time so callers can publish auditable progress events. */
    public ToolExecution execute(AnalysisPlan plan, java.util.function.Consumer<ToolAudit> auditConsumer) {
        AnalysisPeriodResolver.ResolvedPeriod current = periodResolver.resolve(plan.scope().timeRange());
        Map<String, Object> evidence = new LinkedHashMap<>();
        List<ToolAudit> toolAudits = new ArrayList<>();
        switch (plan.intent()) {
            case METRIC -> {
                evidence.put("operationOverview", invoke(toolAudits, auditConsumer, "queryOperationOverview",
                        Map.of("region", plan.scope().region(), "startDate", current.startDate(), "endDate", current.endDate()),
                        () -> queryTools.queryOperationOverview(plan.scope().region(), current.startDate(), current.endDate())));
            }
            case TREND -> {
                String groupName = resolveGroup(plan, evidence);
                evidence.put("gmvTrend", invoke(toolAudits, auditConsumer, "queryOperationTrend",
                        Map.of("region", plan.scope().region(), "group", groupName, "metric", "GMV_AMOUNT", "startDate", current.startDate(), "endDate", current.endDate()),
                        () -> queryTools.queryOperationTrend(plan.scope().region(), groupName,
                                AnalyticsQueryTools.TrendMetric.GMV_AMOUNT, current.startDate(), current.endDate())));
            }
            case FAULT_ANALYSIS -> {
                if (plan.scope().group() == null || plan.scope().group().isBlank()) {
                    String faultCode = faultCode(plan.metrics());
                    Map<String, Object> parameters = new LinkedHashMap<>();
                    parameters.put("region", plan.scope().region());
                    parameters.put("faultCode", faultCode == null ? "全部故障" : faultCode);
                    parameters.put("startDate", current.startDate());
                    parameters.put("endDate", current.endDate());
                    parameters.put("limit", 5);
                    evidence.put("faultGroupRanking", invoke(toolAudits, auditConsumer, "rankFaultGroups", parameters,
                            () -> queryTools.rankFaultGroups(plan.scope().region(), faultCode,
                                    current.startDate(), current.endDate(), 5)));
                } else {
                    String groupName = resolveGroup(plan, evidence);
                    evidence.put("faultBreakdown", invoke(toolAudits, auditConsumer, "queryFaultBreakdown",
                            Map.of("region", plan.scope().region(), "group", groupName, "startDate", current.startDate(), "endDate", current.endDate(), "limit", 10),
                            () -> queryTools.queryFaultBreakdown(plan.scope().region(), groupName,
                                    current.startDate(), current.endDate(), 10)));
                }
            }
            case ANOMALY_ROOT_CAUSE -> {
                String groupName = resolveGroup(plan, evidence);
                AnalysisPeriodResolver.ResolvedPeriod previous = periodResolver.previousPeriod(current);
                evidence.put("periodComparison", invoke(toolAudits, auditConsumer, "compareAnomalyEvidence",
                        Map.ofEntries(
                                Map.entry("region", plan.scope().region()), Map.entry("group", groupName),
                                Map.entry("currentStartDate", current.startDate()), Map.entry("currentEndDate", current.endDate()),
                                Map.entry("previousStartDate", previous.startDate()), Map.entry("previousEndDate", previous.endDate())),
                        () -> queryTools.compareAnomalyEvidence(plan.scope().region(), groupName,
                                current.startDate(), current.endDate(), previous.startDate(), previous.endDate())));
                evidence.put("gmvTrend", trend(toolAudits, auditConsumer, plan.scope().region(), groupName, AnalyticsQueryTools.TrendMetric.GMV_AMOUNT, current));
                evidence.put("availabilityTrend", trend(toolAudits, auditConsumer, plan.scope().region(), groupName, AnalyticsQueryTools.TrendMetric.AVAILABLE_PILE_COUNT, current));
                evidence.put("offlineRateTrend", trend(toolAudits, auditConsumer, plan.scope().region(), groupName, AnalyticsQueryTools.TrendMetric.OFFLINE_RATE, current));
                evidence.put("offlineGroupRanking", invoke(toolAudits, auditConsumer, "rankGroups",
                        Map.of("region", plan.scope().region(), "metric", "OFFLINE_RATE", "startDate", current.startDate(), "endDate", current.endDate(), "limit", 5),
                        () -> queryTools.rankGroups(plan.scope().region(),
                                AnalyticsQueryTools.GroupRankingMetric.OFFLINE_RATE, current.startDate(), current.endDate(), 5)));
            }
            case RANKING -> {
                if (plan.metrics().contains("communication_timeout")) {
                    evidence.put("faultGroupRanking", invoke(toolAudits, auditConsumer, "rankFaultGroups",
                            Map.of("region", plan.scope().region(), "faultCode", "COMMUNICATION_TIMEOUT",
                                    "startDate", current.startDate(), "endDate", current.endDate(), "limit", 5),
                            () -> queryTools.rankFaultGroups(plan.scope().region(), "COMMUNICATION_TIMEOUT",
                                    current.startDate(), current.endDate(), 5)));
                    break;
                }
                AnalyticsQueryTools.GroupRankingMetric metric = rankingMetric(plan.metrics());
                evidence.put("groupRanking", invoke(toolAudits, auditConsumer, "rankGroups",
                        Map.of("region", plan.scope().region(), "metric", metric.name(), "startDate", current.startDate(), "endDate", current.endDate(), "limit", 5),
                        () -> queryTools.rankGroups(plan.scope().region(), metric, current.startDate(), current.endDate(), 5)));
            }
            case AD_HOC_QUERY -> throw new IllegalArgumentException("临时查询必须由受限 Text-to-SQL 节点执行");
            default -> throw new IllegalArgumentException("不支持的分析意图：" + plan.intent());
        }
        evidence.put("resolvedPeriod", current);
        return new ToolExecution(toolAudits.stream().map(ToolAudit::toolName).distinct().toList(), evidence, toolAudits);
    }

    /**
     * Executes one root-cause evidence tool. The runtime calls this method between
     * observations, so a follow-up is selected from observed data rather than eagerly
     * running every available tool.
     */
    public ToolExecution executeAnomalyStep(AnalysisPlan plan, AgentDecision decision,
                                            Map<String, Object> previousEvidence,
                                            java.util.function.Consumer<ToolAudit> auditConsumer) {
        if (plan.intent() != AnalysisPlan.Intent.ANOMALY_ROOT_CAUSE) {
            throw new IllegalArgumentException("仅异常归因计划可按步骤执行");
        }
        if (decision.allowedTools().size() != 1) {
            throw new IllegalArgumentException("异常归因步骤必须只允许一个工具");
        }
        String tool = decision.allowedTools().get(0);
        Map<String, Object> evidence = new LinkedHashMap<>();
        List<ToolAudit> toolAudits = new ArrayList<>();
        AnalysisPeriodResolver.ResolvedPeriod current = previousEvidence.get("resolvedPeriod") instanceof AnalysisPeriodResolver.ResolvedPeriod value
                ? value : periodResolver.resolve(plan.scope().timeRange());
        String groupName = resolveGroup(plan, evidence, previousEvidence);
        switch (tool) {
            case "compareAnomalyEvidence" -> {
                AnalysisPeriodResolver.ResolvedPeriod previous = periodResolver.previousPeriod(current);
                evidence.put("periodComparison", invoke(toolAudits, auditConsumer, tool,
                        Map.ofEntries(
                                Map.entry("region", plan.scope().region()), Map.entry("group", groupName),
                                Map.entry("currentStartDate", current.startDate()), Map.entry("currentEndDate", current.endDate()),
                                Map.entry("previousStartDate", previous.startDate()), Map.entry("previousEndDate", previous.endDate())),
                        () -> queryTools.compareAnomalyEvidence(plan.scope().region(), groupName,
                                current.startDate(), current.endDate(), previous.startDate(), previous.endDate())));
            }
            case "rankGroups" -> evidence.put("offlineGroupRanking", invoke(toolAudits, auditConsumer, tool,
                    Map.of("region", plan.scope().region(), "metric", "OFFLINE_RATE", "startDate", current.startDate(), "endDate", current.endDate(), "limit", 5),
                    () -> queryTools.rankGroups(plan.scope().region(),
                            AnalyticsQueryTools.GroupRankingMetric.OFFLINE_RATE, current.startDate(), current.endDate(), 5)));
            case "queryOperationTrend" -> {
                AnalyticsQueryTools.TrendMetric metric = decision.expectedEvidence().contains("可用桩趋势")
                        ? AnalyticsQueryTools.TrendMetric.AVAILABLE_PILE_COUNT : AnalyticsQueryTools.TrendMetric.GMV_AMOUNT;
                String key = metric == AnalyticsQueryTools.TrendMetric.AVAILABLE_PILE_COUNT ? "availabilityTrend" : "gmvTrend";
                evidence.put(key, trend(toolAudits, auditConsumer, plan.scope().region(), groupName, metric, current));
            }
            default -> throw new IllegalArgumentException("异常归因步骤不支持工具：" + tool);
        }
        evidence.put("resolvedPeriod", current);
        return new ToolExecution(toolAudits.stream().map(ToolAudit::toolName).distinct().toList(), evidence, toolAudits);
    }

    private String resolveGroup(AnalysisPlan plan, Map<String, Object> evidence) {
        GroupEntityResolver.ResolvedGroup resolvedGroup = groupEntityResolver.resolve(
                plan.scope().region(), plan.scope().city(), plan.scope().group());
        evidence.put("entityResolution", resolvedGroup);
        return resolvedGroup.matchedGroup().groupName();
    }

    private String resolveGroup(AnalysisPlan plan, Map<String, Object> evidence, Map<String, Object> previousEvidence) {
        Object previous = previousEvidence.get("entityResolution");
        if (previous instanceof GroupEntityResolver.ResolvedGroup resolved) {
            evidence.put("entityResolution", resolved);
            return resolved.matchedGroup().groupName();
        }
        return resolveGroup(plan, evidence);
    }

    private AnalyticsQueryTools.GroupRankingMetric rankingMetric(List<String> metrics) {
        if (metrics.contains("offline_rate") || metrics.contains("offline_pile_count")) {
            return AnalyticsQueryTools.GroupRankingMetric.OFFLINE_RATE;
        }
        if (metrics.contains("fault_pile_days") || metrics.contains("communication_timeout")) {
            return AnalyticsQueryTools.GroupRankingMetric.FAULT_PILE_DAYS;
        }
        return AnalyticsQueryTools.GroupRankingMetric.GMV_LOW;
    }

    private String faultCode(List<String> metrics) {
        return metrics.stream().anyMatch(metric -> metric.equalsIgnoreCase("communication_timeout")
                || metric.equals("通信故障")) ? "COMMUNICATION_TIMEOUT" : null;
    }

    private List<AnalyticsQueryTools.TrendPoint> trend(List<ToolAudit> toolAudits, java.util.function.Consumer<ToolAudit> auditConsumer, String region, String group,
            AnalyticsQueryTools.TrendMetric metric, AnalysisPeriodResolver.ResolvedPeriod period) {
        return invoke(toolAudits, auditConsumer, "queryOperationTrend",
                Map.of("region", region, "group", group, "metric", metric.name(), "startDate", period.startDate(), "endDate", period.endDate()),
                () -> queryTools.queryOperationTrend(region, group, metric, period.startDate(), period.endDate()));
    }

    private <T> T invoke(List<ToolAudit> toolAudits, java.util.function.Consumer<ToolAudit> auditConsumer, String toolName, Map<String, Object> parameters, Supplier<T> action) {
        long startedAt = System.nanoTime();
        T result = action.get();
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        ToolAudit audit = new ToolAudit(toolName, parameters, durationMs, summarize(result));
        toolAudits.add(audit);
        auditConsumer.accept(audit);
        return result;
    }

    private String summarize(Object result) {
        if (result instanceof List<?> rows) {
            return "rows=" + rows.size();
        }
        return "resultType=" + result.getClass().getSimpleName();
    }

    public record ToolExecution(List<String> invokedTools, Map<String, Object> evidence, List<ToolAudit> toolAudits) { }
    public record ToolAudit(String toolName, Map<String, Object> parameters, long durationMs, String resultSummary) { }
}
