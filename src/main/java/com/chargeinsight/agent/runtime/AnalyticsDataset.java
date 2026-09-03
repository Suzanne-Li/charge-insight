package com.chargeinsight.agent.runtime;

import com.chargeinsight.agent.planning.AnalysisPlan;
import com.chargeinsight.agent.sql.TextToSqlFallbackService;
import com.chargeinsight.agent.tool.AnalyticsQueryTools;
import com.chargeinsight.agent.tool.AnalyticsToolRouter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable result contract shared by answer generation, tables and follow-up analysis. */
public record AnalyticsDataset(String title, List<Field> fields, List<Map<String, Object>> rows) {
    public static AnalyticsDataset from(AnalysisPlan plan, AnalyticsToolRouter.ToolExecution execution) {
        Object controlledSql = execution.evidence().get("controlledSql");
        if (controlledSql instanceof TextToSqlFallbackService.FallbackExecution value) return controlledSql(value);
        Object overview = execution.evidence().get("operationOverview");
        if (overview instanceof AnalyticsQueryTools.OperationOverview value) return overview(value);
        Object ranking = execution.evidence().get("groupRanking");
        if (ranking instanceof List<?> values) return ranking(values);
        Object faultRanking = execution.evidence().get("faultGroupRanking");
        if (faultRanking instanceof List<?> values) return faultRanking(values);
        Object faults = execution.evidence().get("faultBreakdown");
        if (faults instanceof List<?> values) return faults(values);
        // Root-cause execution also contains trend lists. Prefer its primary period
        // comparison contract before considering supporting trend evidence.
        Object comparison = execution.evidence().get("periodComparison");
        if (comparison instanceof AnalyticsQueryTools.AnomalyEvidence value) return comparison(value);
        Object trend = execution.evidence().get("gmvTrend");
        if (trend instanceof List<?> values) return trend(values);
        return new AnalyticsDataset("分析结果", List.of(), List.of());
    }

    private static AnalyticsDataset controlledSql(TextToSqlFallbackService.FallbackExecution value) {
        if (value.rows().isEmpty()) return new AnalyticsDataset(value.title(), List.of(), List.of());
        Map<String, Object> first = value.rows().get(0);
        List<Field> fields = first.entrySet().stream().map(entry -> new Field(entry.getKey(), entry.getKey(),
                fieldType(entry.getValue()), "")).toList();
        return new AnalyticsDataset(value.title(), fields, value.rows());
    }

    private static String fieldType(Object value) {
        if (value instanceof Number) return "NUMBER";
        if (value instanceof java.time.temporal.TemporalAccessor || value instanceof java.util.Date) return "DATE";
        return "TEXT";
    }

    private static AnalyticsDataset overview(AnalyticsQueryTools.OperationOverview value) {
        List<Field> fields = List.of(
                new Field("snapshotDate", "数据日期", "DATE", ""),
                new Field("availablePileCount", "可用桩", "NUMBER", "个"),
                new Field("avgAvailablePileCount", "日均可用桩", "NUMBER", "个"),
                new Field("energyKwh", "累计充电量", "NUMBER", "kWh"),
                new Field("gmvAmount", "累计 GMV", "NUMBER", "元"),
                new Field("orderCount", "累计订单量", "NUMBER", "单"),
                new Field("shareSuccessRate", "共享成功率", "PERCENT", "%"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("snapshotDate", value.snapshotDate() == null ? "" : value.snapshotDate());
        row.put("availablePileCount", value.availablePileCount());
        row.put("avgAvailablePileCount", value.avgAvailablePileCount());
        row.put("energyKwh", value.energyKwh());
        row.put("gmvAmount", value.gmvAmount());
        row.put("orderCount", value.orderCount());
        row.put("shareSuccessRate", value.shareSuccessRate());
        return new AnalyticsDataset(value.region() + "区域运营总览", fields, List.of(Map.copyOf(row)));
    }

    @SuppressWarnings("unchecked")
    private static AnalyticsDataset ranking(List<?> values) {
        List<AnalyticsQueryTools.GroupRanking> rankings = (List<AnalyticsQueryTools.GroupRanking>) values;
        List<Field> fields = List.of(new Field("groupName", "桩群", "TEXT", ""),
                new Field("gmvAmount", "GMV", "NUMBER", "元"),
                new Field("offlineRate", "离线率", "PERCENT", "%"),
                new Field("faultPileDays", "故障桩日", "NUMBER", "桩日"));
        List<Map<String, Object>> rows = rankings.stream().map(row -> {
            Map<String, Object> valuesMap = new LinkedHashMap<>();
            valuesMap.put("groupName", row.groupName());
            valuesMap.put("gmvAmount", row.gmvAmount());
            valuesMap.put("offlineRate", row.offlineRate());
            valuesMap.put("faultPileDays", row.faultPileDays());
            return Map.copyOf(valuesMap);
        }).toList();
        return new AnalyticsDataset("桩群排行", fields, rows);
    }

    @SuppressWarnings("unchecked")
    private static AnalyticsDataset faultRanking(List<?> values) {
        List<AnalyticsQueryTools.FaultGroupRanking> rankings = (List<AnalyticsQueryTools.FaultGroupRanking>) values;
        List<Field> fields = List.of(new Field("groupName", "桩群", "TEXT", ""),
                new Field("faultPileDays", "故障桩日", "NUMBER", "桩日"),
                new Field("faultDurationMinutes", "累计故障时长", "NUMBER", "分钟"),
                new Field("affectedOrderDays", "影响订单日计数", "NUMBER", "次"));
        List<Map<String, Object>> rows = rankings.stream().map(row -> {
            Map<String, Object> valuesMap = new LinkedHashMap<>();
            valuesMap.put("groupName", row.groupName());
            valuesMap.put("faultPileDays", row.faultPileDays());
            valuesMap.put("faultDurationMinutes", row.faultDurationMinutes());
            valuesMap.put("affectedOrderDays", row.affectedOrderDays());
            return Map.copyOf(valuesMap);
        }).toList();
        return new AnalyticsDataset("故障桩群排行", fields, rows);
    }

    @SuppressWarnings("unchecked")
    private static AnalyticsDataset faults(List<?> values) {
        List<AnalyticsQueryTools.FaultBreakdown> faults = (List<AnalyticsQueryTools.FaultBreakdown>) values;
        List<Field> fields = List.of(new Field("faultType", "故障类型", "TEXT", ""),
                new Field("faultCode", "故障编码", "TEXT", ""),
                new Field("faultPileDays", "故障桩日", "NUMBER", "桩日"),
                new Field("faultDurationMinutes", "累计故障时长", "NUMBER", "分钟"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AnalyticsQueryTools.FaultBreakdown row : faults) {
            Map<String, Object> valuesMap = new LinkedHashMap<>();
            valuesMap.put("faultType", row.faultType());
            valuesMap.put("faultCode", row.faultCode());
            valuesMap.put("faultPileDays", row.faultPileDays());
            valuesMap.put("faultDurationMinutes", row.faultDurationMinutes());
            rows.add(Map.copyOf(valuesMap));
        }
        return new AnalyticsDataset("故障分析", fields, List.copyOf(rows));
    }

    private static AnalyticsDataset comparison(AnalyticsQueryTools.AnomalyEvidence value) {
        List<Field> fields = List.of(new Field("period", "周期", "TEXT", ""),
                new Field("gmvAmount", "GMV", "NUMBER", "元"),
                new Field("availablePileCount", "期末可用桩", "NUMBER", "个"),
                new Field("orderCount", "订单量", "NUMBER", "单"),
                new Field("offlineRate", "离线率", "PERCENT", "%"));
        return new AnalyticsDataset("周期对比", fields, List.of(comparisonRow("本期", value.currentPeriod()),
                comparisonRow("上期", value.previousPeriod())));
    }

    @SuppressWarnings("unchecked")
    private static AnalyticsDataset trend(List<?> values) {
        List<AnalyticsQueryTools.TrendPoint> points = (List<AnalyticsQueryTools.TrendPoint>) values;
        List<Field> fields = List.of(new Field("statDate", "日期", "DATE", ""),
                new Field("metricValue", "GMV", "NUMBER", "元"));
        List<Map<String, Object>> rows = points.stream().map(point -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("statDate", point.statDate());
            row.put("metricValue", point.value());
            return Map.copyOf(row);
        }).toList();
        return new AnalyticsDataset("GMV 趋势", fields, rows);
    }

    private static Map<String, Object> comparisonRow(String period, AnalyticsQueryTools.OperationOverview value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("period", period);
        row.put("gmvAmount", value.gmvAmount());
        row.put("availablePileCount", value.availablePileCount());
        row.put("orderCount", value.orderCount());
        row.put("offlineRate", value.offlineRate());
        return Map.copyOf(row);
    }

    public record Field(String key, String label, String type, String unit) { }
}
