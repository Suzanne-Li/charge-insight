package com.chargeinsight.agent.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/** Parses only the bounded planner schema; malformed model output never reaches domain tools. */
@Component
public class AnalysisPlanParser {
    private final ObjectMapper objectMapper;

    public AnalysisPlanParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public AnalysisPlan parse(String rawPlan) {
        try {
            AnalysisPlan plan = objectMapper.readValue(stripCodeFence(rawPlan), AnalysisPlan.class);
            validate(plan);
            return new AnalysisPlan(plan.intent(), plan.metrics().stream().map(MetricNameNormalizer::normalize).distinct().toList(),
                    plan.scope(), defaultIfEmpty(plan.steps(), defaultSteps(plan.intent())),
                    defaultIfEmpty(plan.evidenceNeeded(), defaultEvidence(plan.intent())));
        } catch (JsonProcessingException exception) {
            throw new InvalidAnalysisPlanException("规划结果不是有效 JSON", exception);
        }
    }

    private void validate(AnalysisPlan plan) {
        if (plan == null || plan.intent() == null) {
            throw new InvalidAnalysisPlanException("缺少 intent");
        }
        if (plan.scope() == null || isBlank(plan.scope().timeRange())
                || (plan.intent() != AnalysisPlan.Intent.RANKING && isBlank(plan.scope().region()))) {
            throw new InvalidAnalysisPlanException("scope 必须包含 region、timeRange；仅 RANKING 可省略 region");
        }
        if (requiresGroup(plan.intent()) && isBlank(plan.scope().group())) {
            throw new InvalidAnalysisPlanException(plan.intent() + " 问题的 scope 必须包含 group");
        }
        validateTextList("metrics", plan.metrics());
    }

    private boolean requiresGroup(AnalysisPlan.Intent intent) {
        return intent == AnalysisPlan.Intent.TREND
                || intent == AnalysisPlan.Intent.ANOMALY_ROOT_CAUSE;
    }

    private void validateTextList(String field, List<String> values) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(this::isBlank)) {
            throw new InvalidAnalysisPlanException(field + " 必须是非空字符串数组");
        }
    }

    private List<String> defaultIfEmpty(List<String> values, List<String> defaults) {
        return values == null || values.isEmpty() || values.stream().anyMatch(this::isBlank) ? defaults : values;
    }

    private List<String> defaultSteps(AnalysisPlan.Intent intent) {
        return switch (intent) {
            case METRIC -> List.of("查询运营总览", "核对指标口径", "生成运营回答");
            case RANKING -> List.of("查询桩群排行", "核对排序指标", "生成运营回答");
            case FAULT_ANALYSIS -> List.of("查询故障统计", "定位高风险桩群", "生成运营建议");
            case TREND -> List.of("查询周期趋势", "比较变化情况", "生成趋势结论");
            case ANOMALY_ROOT_CAUSE -> List.of("本期与上期对比", "核对可用桩和订单变化", "核对故障数据", "生成原因判断");
            case AD_HOC_QUERY -> List.of("匹配表结构与 SQL 示例", "生成并校验只读 SQL", "执行查询", "检查结果", "生成运营回答");
        };
    }

    private List<String> defaultEvidence(AnalysisPlan.Intent intent) {
        return switch (intent) {
            case METRIC -> List.of("周期指标汇总");
            case RANKING -> List.of("受控排行结果");
            case FAULT_ANALYSIS -> List.of("故障类型与影响统计");
            case TREND -> List.of("周期趋势数据");
            case ANOMALY_ROOT_CAUSE -> List.of("同期对比", "可用桩与订单变化", "故障统计");
            case AD_HOC_QUERY -> List.of("生成 SQL", "SQL 执行结果");
        };
    }

    private String stripCodeFence(String rawPlan) {
        if (rawPlan == null) {
            return "";
        }
        String trimmed = rawPlan.trim();
        if (trimmed.startsWith("```json") && trimmed.endsWith("```")) {
            return trimmed.substring(7, trimmed.length() - 3).trim();
        }
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            return trimmed.substring(3, trimmed.length() - 3).trim();
        }
        return trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static class InvalidAnalysisPlanException extends RuntimeException {
        public InvalidAnalysisPlanException(String message) {
            super(message);
        }

        public InvalidAnalysisPlanException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
