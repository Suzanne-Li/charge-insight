package com.chargeinsight.agent.runtime;

import com.chargeinsight.agent.planning.AnalysisPlan;
import com.chargeinsight.agent.tool.AnalysisPeriodResolver;
import com.chargeinsight.agent.tool.AnalyticsToolRouter;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Deterministically verifies Dataset structure, query scope and suspicious numeric values. */
@Service
public class AnalyticsResultChecker {
    private final AnalysisPeriodResolver periodResolver;

    public AnalyticsResultChecker(AnalysisPeriodResolver periodResolver) {
        this.periodResolver = periodResolver;
    }

    public CheckResult check(AnalysisPlan plan, AnalyticsDataset dataset,
                             AnalyticsToolRouter.ToolExecution execution) {
        if (dataset == null || dataset.fields().isEmpty() || dataset.rows().isEmpty()) {
            return invalid("NO_DATA", "当前查询范围没有可用于回答的数据");
        }
        Set<String> fieldKeys = new LinkedHashSet<>();
        for (AnalyticsDataset.Field field : dataset.fields()) {
            if (!fieldKeys.add(field.key())) return invalid("INVALID", "Dataset 包含重复字段：" + field.key());
        }
        List<String> missingFields = requiredFields(plan, dataset.title()).stream()
                .filter(field -> !fieldKeys.contains(field)).toList();
        if (!missingFields.isEmpty()) return invalid("INVALID", "Dataset 缺少必需字段：" + String.join("、", missingFields));
        for (Map<String, Object> row : dataset.rows()) {
            List<String> missingRowValues = fieldKeys.stream().filter(field -> !row.containsKey(field)).toList();
            if (!missingRowValues.isEmpty()) {
                return invalid("INVALID", "Dataset 行缺少字段：" + String.join("、", missingRowValues));
            }
        }
        String scopeError = validateScope(plan, execution);
        if (scopeError != null) return invalid("INVALID", scopeError);

        Set<String> warnings = new LinkedHashSet<>();
        for (Map<String, Object> row : dataset.rows()) {
            for (AnalyticsDataset.Field field : dataset.fields()) {
                Object value = row.get(field.key());
                if (value instanceof Number number) {
                    double decimalValue = number.doubleValue();
                    if (!Double.isFinite(decimalValue)) return invalid("INVALID", "查询结果包含非有限数值：" + field.label());
                    if (decimalValue < 0) warnings.add("查询结果包含负数指标，请核对源数据或指标口径");
                    if ("PERCENT".equals(field.type()) && decimalValue > 1) {
                        warnings.add(field.label() + " 超出 0%～100% 范围，请核对源数据或指标口径");
                    }
                }
            }
        }
        if (plan.intent() == AnalysisPlan.Intent.METRIC) {
            Object snapshotDate = dataset.rows().get(0).get("snapshotDate");
            if (snapshotDate == null || String.valueOf(snapshotDate).isBlank()) {
                return invalid("NO_DATA", "统计周期内没有可用的日快照");
            }
        }
        return new CheckResult(warnings.isEmpty() ? "VALID" : "VALID_WITH_WARNINGS", List.copyOf(warnings));
    }

    private String validateScope(AnalysisPlan plan, AnalyticsToolRouter.ToolExecution execution) {
        if (execution == null || execution.toolAudits().isEmpty()) return "查询执行缺少可审计的工具调用";
        String expectedRegion = plan.scope().region() == null ? "" : plan.scope().region().trim();
        if (expectedRegion.isEmpty()) return "查询计划缺少区域范围";
        AnalysisPeriodResolver.ResolvedPeriod expectedPeriod;
        try {
            expectedPeriod = periodResolver.resolve(plan.scope().timeRange());
        } catch (RuntimeException exception) {
            return "查询计划包含不可解析的时间范围";
        }
        for (AnalyticsToolRouter.ToolAudit audit : execution.toolAudits()) {
            Map<String, Object> parameters = audit.parameters();
            Object region = parameters.get("region");
            if (region != null && !expectedRegion.equals(String.valueOf(region).trim())) {
                return "工具查询区域与计划范围不一致";
            }
            String dateError = validateDateRange(parameters, "startDate", "endDate", expectedPeriod);
            if (dateError != null) return dateError;
            dateError = validateDateRange(parameters, "currentStartDate", "currentEndDate", expectedPeriod);
            if (dateError != null) return dateError;
        }
        return null;
    }

    private String validateDateRange(Map<String, Object> parameters, String startKey, String endKey,
                                     AnalysisPeriodResolver.ResolvedPeriod expected) {
        if (!parameters.containsKey(startKey) && !parameters.containsKey(endKey)) return null;
        try {
            LocalDate start = date(parameters.get(startKey));
            LocalDate end = date(parameters.get(endKey));
            return expected.startDate().equals(start) && expected.endDate().equals(end)
                    ? null : "工具查询时间与计划范围不一致";
        } catch (RuntimeException exception) {
            return "工具查询包含无效时间范围";
        }
    }

    private LocalDate date(Object value) {
        if (value instanceof LocalDate date) return date;
        if (value == null) throw new IllegalArgumentException("missing date");
        return LocalDate.parse(String.valueOf(value));
    }

    private List<String> requiredFields(AnalysisPlan plan, String title) {
        return switch (plan.intent()) {
            case METRIC -> List.of("snapshotDate", "availablePileCount", "energyKwh", "gmvAmount");
            case RANKING -> plan.metrics().contains("communication_timeout") || title.contains("故障桩群排行")
                    ? List.of("groupName", "faultPileDays", "faultDurationMinutes", "affectedOrderDays")
                    : List.of("groupName", "gmvAmount", "offlineRate", "faultPileDays");
            case TREND -> List.of("statDate", "metricValue");
            case ANOMALY_ROOT_CAUSE -> List.of("period", "gmvAmount", "availablePileCount", "orderCount", "offlineRate");
            case FAULT_ANALYSIS -> title.contains("桩群排行")
                    ? List.of("groupName", "faultPileDays", "faultDurationMinutes", "affectedOrderDays")
                    : List.of("faultType", "faultCode", "faultPileDays", "faultDurationMinutes");
            case AD_HOC_QUERY -> List.of();
        };
    }

    private CheckResult invalid(String status, String warning) {
        return new CheckResult(status, List.of(warning));
    }

    public record CheckResult(String status, List<String> warnings) {
        public boolean answerable() { return status.startsWith("VALID"); }
    }
}
