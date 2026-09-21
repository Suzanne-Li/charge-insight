package com.chargeinsight.agent.runtime;

import com.chargeinsight.agent.planning.AnalysisPlan;
import com.chargeinsight.agent.sql.TextToSqlFallbackService;
import com.chargeinsight.agent.tool.AnalyticsQueryTools;
import com.chargeinsight.agent.tool.AnalyticsToolRouter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Renders bounded tool output as concise business language, never as internal evidence metadata. */
final class BusinessAnswerRenderer {
    private BusinessAnswerRenderer() { }

    static String render(AnalysisPlan plan, AnalyticsToolRouter.ToolExecution execution) {
        Object controlledSql = execution.evidence().get("controlledSql");
        if (controlledSql instanceof TextToSqlFallbackService.FallbackExecution value) return controlledSql(value);
        Object overview = execution.evidence().get("operationOverview");
        if (overview instanceof AnalyticsQueryTools.OperationOverview value) return overview(value);
        Object ranking = execution.evidence().get("groupRanking");
        if (ranking instanceof List<?> values) return ranking(values, plan);
        Object faultRanking = execution.evidence().get("faultGroupRanking");
        if (faultRanking instanceof List<?> values) return faultRanking(values, plan);
        Object faults = execution.evidence().get("faultBreakdown");
        if (faults instanceof List<?> values) return faults(values);
        Object comparison = execution.evidence().get("periodComparison");
        if (comparison instanceof AnalyticsQueryTools.AnomalyEvidence value) return anomaly(value, execution);
        return "本次未获得可展示的运营数据，请调整问题范围后重试。";
    }

    private static String controlledSql(TextToSqlFallbackService.FallbackExecution value) {
        if (value.rows().isEmpty()) return "按当前区域和时间范围没有查询到符合条件的数据。";
        StringBuilder answer = new StringBuilder(value.title()).append("：\n\n")
                .append(value.explanation()).append("\n\n共查询到 ").append(value.rows().size()).append(" 条结果，详细数据见下方表格。");
        if (value.rows().size() == 1) {
            answer.append("\n");
            value.rows().get(0).forEach((field, fieldValue) -> answer.append("\n• ").append(field).append("：").append(fieldValue));
        }
        return answer.toString();
    }

    private static String overview(AnalyticsQueryTools.OperationOverview value) {
        BigDecimal price = divide(value.gmvAmount(), value.energyKwh());
        long days = java.time.temporal.ChronoUnit.DAYS.between(value.startDate(), value.endDate()) + 1;
        BigDecimal dailyEnergy = divide(value.energyKwh(), BigDecimal.valueOf(days));
        return "%s区域 %s 至 %s 的运营数据如下：\n\n"
                .formatted(value.region(), value.startDate(), value.endDate())
                + "• 可用桩：%s 个（%s 快照）\n• 累计充电量：%s kWh\n• 累计 GMV：%s 元\n"
                .formatted(value.availablePileCount(), value.snapshotDate(), value.energyKwh(), value.gmvAmount())
                + "• 累计订单量：%s 单，共享成功率：%s\n\n"
                .formatted(value.orderCount(), percent(value.shareSuccessRate()))
                + "数据分析：周期内日均可用桩为 %s 个，日均充电量约 %s kWh；平均每度电产生 %s 元 GMV。"
                .formatted(value.avgAvailablePileCount(), dailyEnergy, price);
    }

    private static String ranking(List<?> values, AnalysisPlan plan) {
        @SuppressWarnings("unchecked") List<AnalyticsQueryTools.GroupRanking> rows = (List<AnalyticsQueryTools.GroupRanking>) values;
        if (rows.isEmpty()) return "该时间范围内没有可用于排行的桩群数据。";
        String heading = rows.get(0).rankingMetric() == AnalyticsQueryTools.GroupRankingMetric.FAULT_PILE_DAYS
                ? "通信与故障影响较多的桩群" : "桩群排行";
        StringBuilder answer = new StringBuilder(heading).append("（").append(plan.scope().timeRange()).append("）：\n");
        for (int i = 0; i < rows.size(); i++) {
            AnalyticsQueryTools.GroupRanking row = rows.get(i);
            answer.append(i + 1).append(". ").append(row.groupName()).append("：");
            if (row.rankingMetric() == AnalyticsQueryTools.GroupRankingMetric.FAULT_PILE_DAYS) {
                answer.append("故障桩日 ").append(row.faultPileDays()).append("，离线率 ").append(percent(row.offlineRate())).append("\n");
            } else if (row.rankingMetric() == AnalyticsQueryTools.GroupRankingMetric.OFFLINE_RATE) {
                answer.append("离线率 ").append(percent(row.offlineRate())).append("，日均可用桩 ").append(row.avgAvailablePileCount()).append("\n");
            } else {
                answer.append("GMV ").append(row.gmvAmount()).append(" 元\n");
            }
        }
        return answer.toString();
    }

    private static String faults(List<?> values) {
        @SuppressWarnings("unchecked") List<AnalyticsQueryTools.FaultBreakdown> rows = (List<AnalyticsQueryTools.FaultBreakdown>) values;
        if (rows.isEmpty()) return "该时间范围内没有查询到故障记录。";
        StringBuilder answer = new StringBuilder("故障分析结果：\n");
        for (AnalyticsQueryTools.FaultBreakdown row : rows) {
            answer.append("• ").append(row.faultType()).append("（").append(row.faultCode()).append("）：故障桩日 ")
                    .append(row.faultPileDays()).append("，累计故障时长 ").append(row.faultDurationMinutes()).append(" 分钟\n");
        }
        return answer.toString();
    }

    private static String faultRanking(List<?> values, AnalysisPlan plan) {
        @SuppressWarnings("unchecked") List<AnalyticsQueryTools.FaultGroupRanking> rows =
                (List<AnalyticsQueryTools.FaultGroupRanking>) values;
        if (rows.isEmpty()) return "该时间范围内没有查询到符合条件的桩群故障记录。";
        StringBuilder answer = new StringBuilder(plan.scope().region()).append("区域")
                .append(plan.scope().timeRange()).append("故障较多的桩群如下：\n");
        for (int i = 0; i < rows.size(); i++) {
            AnalyticsQueryTools.FaultGroupRanking row = rows.get(i);
            answer.append(i + 1).append(". ").append(row.groupName()).append("：故障桩日 ")
                    .append(row.faultPileDays()).append("，累计故障时长 ").append(row.faultDurationMinutes())
                    .append(" 分钟，影响订单日计数 ").append(row.affectedOrderDays()).append("。\n");
        }
        answer.append("\n建议优先检查排名靠前桩群的通信链路和设备在线状态。故障桩日为日汇总值，不代表去重故障桩数量。");
        return answer.toString();
    }

    private static String anomaly(AnalyticsQueryTools.AnomalyEvidence value, AnalyticsToolRouter.ToolExecution execution) {
        var current = value.currentPeriod(); var previous = value.previousPeriod();
        BigDecimal gmvChange = current.gmvAmount().subtract(previous.gmvAmount());
        BigDecimal gmvRate = percentChange(current.gmvAmount(), previous.gmvAmount());
        long availableChange = current.availablePileCount() - previous.availablePileCount();
        long orderChange = current.orderCount() - previous.orderCount();
        String direction = gmvChange.signum() < 0 ? "下降" : gmvChange.signum() > 0 ? "上升" : "持平";
        StringBuilder answer = new StringBuilder("本期 GMV 为 ").append(current.gmvAmount()).append(" 元，较上期 ")
                .append(previous.gmvAmount()).append(" 元").append(direction).append(" ")
                .append(gmvChange.abs()).append(" 元（").append(gmvRate.abs()).append("%）。\n\n")
                .append("同期可用桩变化 ").append(signed(availableChange)).append(" 个，订单量变化 ")
                .append(signed(orderChange)).append(" 单，离线率由 ").append(percent(previous.offlineRate()))
                .append(" 变为 ").append(percent(current.offlineRate())).append("。\n");
        if (!value.currentFaults().isEmpty()) {
            AnalyticsQueryTools.FaultBreakdown fault = value.currentFaults().get(0);
            answer.append("本期影响最大的故障为").append(fault.faultType()).append("（").append(fault.faultCode())
                    .append("），累计故障时长 ").append(fault.faultDurationMinutes()).append(" 分钟。\n")
                    .append("现有日汇总数据表明该故障与业务指标变化同期出现，属于需要优先排查的可能原因；是否构成直接因果关系还需逐桩故障与订单时间数据确认。");
        } else {
            answer.append("本期未查询到可用于解释变化的故障汇总，建议继续检查价格、活动、用户需求和调度策略。");
        }
        appendSupplementalEvidence(answer, execution);
        return answer.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendSupplementalEvidence(StringBuilder answer, AnalyticsToolRouter.ToolExecution execution) {
        Object ranking = execution.evidence().get("offlineGroupRanking");
        if (ranking instanceof List<?> values && !values.isEmpty() && values.get(0) instanceof AnalyticsQueryTools.GroupRanking) {
            List<AnalyticsQueryTools.GroupRanking> rows = (List<AnalyticsQueryTools.GroupRanking>) values;
            String groups = rows.stream().limit(3).map(AnalyticsQueryTools.GroupRanking::groupName)
                    .reduce((left, right) -> left + "、" + right).orElse("");
            answer.append("\n补充核验：本期离线率较高的桩群包括 ").append(groups)
                    .append("。该排行用于确定优先排查对象，与 GMV 变化仅是同期相关线索，不构成直接因果证明。");
            return;
        }
        Object availability = execution.evidence().get("availabilityTrend");
        if (availability instanceof List<?> values && !values.isEmpty() && values.get(0) instanceof AnalyticsQueryTools.TrendPoint) {
            List<AnalyticsQueryTools.TrendPoint> points = (List<AnalyticsQueryTools.TrendPoint>) values;
            answer.append("\n补充核验：已获得 ").append(points.size()).append(" 个日度可用桩观测点，用于确认供给变化的发生时段；"
                    + "该时间序列是排查线索，不构成直接因果证明。");
            return;
        }
        Object gmvTrend = execution.evidence().get("gmvTrend");
        if (gmvTrend instanceof List<?> values && !values.isEmpty() && values.get(0) instanceof AnalyticsQueryTools.TrendPoint) {
            List<AnalyticsQueryTools.TrendPoint> points = (List<AnalyticsQueryTools.TrendPoint>) values;
            answer.append("\n补充核验：已获得 ").append(points.size()).append(" 个日度 GMV 观测点，用于区分持续变化与单日波动；"
                    + "该时间序列是排查线索，不构成直接因果证明。");
        }
    }

    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        return denominator == null || denominator.signum() == 0 ? BigDecimal.ZERO : numerator.divide(denominator, 2, RoundingMode.HALF_UP);
    }
    private static BigDecimal percentChange(BigDecimal current, BigDecimal previous) {
        return previous == null || previous.signum() == 0 ? BigDecimal.ZERO
                : current.subtract(previous).multiply(BigDecimal.valueOf(100)).divide(previous, 2, RoundingMode.HALF_UP);
    }
    private static String signed(long value) { return value > 0 ? "+" + value : String.valueOf(value); }
    private static String percent(BigDecimal value) { return value.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%"; }
}
