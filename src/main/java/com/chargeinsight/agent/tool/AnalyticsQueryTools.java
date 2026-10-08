package com.chargeinsight.agent.tool;

import com.chargeinsight.security.RegionAccessPolicy;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Bounded, parameterized read-only tools available to the analytics agent.
 * SQL fragments come exclusively from enums below; all user-derived values are JDBC parameters.
 */
@Service
public class AnalyticsQueryTools {
    private final JdbcTemplate jdbcTemplate;
    private final AnalyticsQueryPolicy policy;
    private final RegionAccessPolicy regionAccessPolicy;

    public AnalyticsQueryTools(JdbcTemplate jdbcTemplate, AnalyticsQueryPolicy policy, RegionAccessPolicy regionAccessPolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.policy = policy;
        this.regionAccessPolicy = regionAccessPolicy;
    }

    public OperationOverview queryOperationOverview(String region, LocalDate startDate, LocalDate endDate) {
        return queryOperationOverview(region, null, startDate, endDate);
    }

    public OperationOverview queryOperationOverview(String region, String city, LocalDate startDate, LocalDate endDate) {
        String safeRegion = policy.requiredScope(region, "region");
        regionAccessPolicy.assertAllowed(safeRegion);
        return queryOperationOverview(safeRegion, policy.optionalScope(city), null, policy.dateRange(startDate, endDate));
    }

    public List<TrendPoint> queryOperationTrend(
            String region, String group, TrendMetric metric, LocalDate startDate, LocalDate endDate) {
        return queryOperationTrend(region, null, group, metric, startDate, endDate);
    }

    public List<TrendPoint> queryOperationTrend(
            String region, String city, String group, TrendMetric metric, LocalDate startDate, LocalDate endDate) {
        String safeRegion = policy.requiredScope(region, "region");
        regionAccessPolicy.assertAllowed(safeRegion);
        String safeCity = policy.optionalScope(city);
        String safeGroup = policy.requiredScope(group, "group");
        AnalyticsQueryPolicy.DateRange range = policy.dateRange(startDate, endDate);
        String sql = """
                SELECT stat_date, %s AS metric_value
                FROM v_daily_group_operation
                WHERE region_name = ?%s AND group_name = ? AND stat_date BETWEEN ? AND ?
                GROUP BY stat_date
                ORDER BY stat_date
                """.formatted(metric.selectExpression(), safeCity == null ? "" : " AND city_name = ?");
        List<Object> arguments = new java.util.ArrayList<>();
        arguments.add(safeRegion);
        if (safeCity != null) arguments.add(safeCity);
        arguments.add(safeGroup);
        arguments.add(sqlDate(range.startDate()));
        arguments.add(sqlDate(range.endDate()));
        return jdbcTemplate.query(sql,
                (row, ignored) -> new TrendPoint(
                        row.getObject("stat_date", LocalDate.class),
                        metric,
                        row.getBigDecimal("metric_value")),
                arguments.toArray());
    }

    public List<GroupRanking> rankGroups(
            String region, GroupRankingMetric metric, LocalDate startDate, LocalDate endDate, Integer limit) {
        return rankGroups(region, null, metric, startDate, endDate, limit);
    }

    public List<GroupRanking> rankGroups(
            String region, String city, GroupRankingMetric metric, LocalDate startDate, LocalDate endDate, Integer limit) {
        boolean allRegions = region == null || region.isBlank();
        String safeRegion = allRegions ? null : policy.requiredScope(region, "region");
        String safeCity = policy.optionalScope(city);
        if (allRegions && safeCity != null) throw new IllegalArgumentException("跨区域排行不能仅指定城市");
        if (allRegions) regionAccessPolicy.assertAllRegionsAllowed();
        else regionAccessPolicy.assertAllowed(safeRegion);
        AnalyticsQueryPolicy.DateRange range = policy.dateRange(startDate, endDate);
        int safeLimit = policy.limit(limit);
        String sql = """
                SELECT group_id, group_name, SUM(gmv_amount) AS gmv_amount,
                       ROUND(AVG(available_pile_count), 2) AS avg_available_pile_count,
                       ROUND(AVG(offline_pile_count), 2) AS avg_offline_pile_count,
                       SUM(fault_pile_count) AS fault_pile_days,
                       MAX(total_pile_count) AS total_pile_count,
                       ROUND(CASE WHEN SUM(total_pile_count) = 0 THEN 0
                                  ELSE SUM(offline_pile_count) / SUM(total_pile_count) END, 4) AS offline_rate
                FROM v_daily_group_operation
                WHERE %s%sstat_date BETWEEN ? AND ?
                GROUP BY group_id, group_name
                ORDER BY %s %s, group_id
                LIMIT ?
                """.formatted(allRegions ? "" : "region_name = ? AND ", safeCity == null ? "" : "city_name = ? AND ",
                metric.orderExpression(), metric.direction());
        List<Object> arguments = new java.util.ArrayList<>();
        if (!allRegions) arguments.add(safeRegion);
        if (safeCity != null) arguments.add(safeCity);
        arguments.add(sqlDate(range.startDate()));
        arguments.add(sqlDate(range.endDate()));
        arguments.add(safeLimit);
        return jdbcTemplate.query(sql,
                (row, ignored) -> new GroupRanking(
                        row.getLong("group_id"), row.getString("group_name"), metric,
                        row.getBigDecimal("gmv_amount"), row.getBigDecimal("avg_available_pile_count"),
                        row.getBigDecimal("avg_offline_pile_count"), row.getLong("fault_pile_days"),
                        row.getLong("total_pile_count"), row.getBigDecimal("offline_rate")),
                arguments.toArray());
    }

    public List<FaultBreakdown> queryFaultBreakdown(
            String region, String group, LocalDate startDate, LocalDate endDate, Integer limit) {
        return queryFaultBreakdown(region, null, group, startDate, endDate, limit);
    }

    public List<FaultBreakdown> queryFaultBreakdown(
            String region, String city, String group, LocalDate startDate, LocalDate endDate, Integer limit) {
        String safeRegion = policy.requiredScope(region, "region");
        regionAccessPolicy.assertAllowed(safeRegion);
        String safeCity = policy.optionalScope(city);
        String safeGroup = policy.requiredScope(group, "group");
        AnalyticsQueryPolicy.DateRange range = policy.dateRange(startDate, endDate);
        int safeLimit = policy.limit(limit);
        String sql = """
                SELECT fault_type, fault_code, SUM(fault_pile_count) AS fault_pile_days,
                       SUM(fault_duration_minutes) AS fault_duration_minutes,
                       SUM(affected_order_count) AS affected_order_days
                FROM v_daily_fault_analysis
                WHERE region_name = ?%s AND group_name = ? AND stat_date BETWEEN ? AND ?
                GROUP BY fault_type, fault_code
                ORDER BY fault_duration_minutes DESC, affected_order_days DESC
                LIMIT ?
                """.formatted(safeCity == null ? "" : " AND city_name = ?");
        List<Object> arguments = new java.util.ArrayList<>();
        arguments.add(safeRegion);
        if (safeCity != null) arguments.add(safeCity);
        arguments.add(safeGroup);
        arguments.add(sqlDate(range.startDate()));
        arguments.add(sqlDate(range.endDate()));
        arguments.add(safeLimit);
        return jdbcTemplate.query(sql,
                (row, ignored) -> new FaultBreakdown(
                        row.getString("fault_type"), row.getString("fault_code"),
                        row.getLong("fault_pile_days"), row.getLong("fault_duration_minutes"), row.getLong("affected_order_days")),
                arguments.toArray());
    }

    public List<FaultGroupRanking> rankFaultGroups(
            String region, String faultCode, LocalDate startDate, LocalDate endDate, Integer limit) {
        return rankFaultGroups(region, null, faultCode, startDate, endDate, limit);
    }

    public List<FaultGroupRanking> rankFaultGroups(
            String region, String city, String faultCode, LocalDate startDate, LocalDate endDate, Integer limit) {
        String safeRegion = policy.requiredScope(region, "region");
        regionAccessPolicy.assertAllowed(safeRegion);
        String safeCity = policy.optionalScope(city);
        AnalyticsQueryPolicy.DateRange range = policy.dateRange(startDate, endDate);
        int safeLimit = policy.limit(limit);
        boolean filterFaultCode = faultCode != null && !faultCode.isBlank();
        String sql = """
                SELECT group_id, group_name,
                       SUM(fault_pile_count) AS fault_pile_days,
                       SUM(fault_duration_minutes) AS fault_duration_minutes,
                       SUM(affected_order_count) AS affected_order_days
                FROM v_daily_fault_analysis
                WHERE region_name = ?%s AND stat_date BETWEEN ? AND ?%s
                GROUP BY group_id, group_name
                ORDER BY fault_pile_days DESC, fault_duration_minutes DESC, group_id
                LIMIT ?
                """.formatted(safeCity == null ? "" : " AND city_name = ?", filterFaultCode ? " AND fault_code = ?" : "");
        List<Object> arguments = new java.util.ArrayList<>();
        arguments.add(safeRegion);
        if (safeCity != null) arguments.add(safeCity);
        arguments.add(sqlDate(range.startDate()));
        arguments.add(sqlDate(range.endDate()));
        if (filterFaultCode) arguments.add(faultCode);
        arguments.add(safeLimit);
        return jdbcTemplate.query(sql,
                (row, ignored) -> new FaultGroupRanking(row.getLong("group_id"), row.getString("group_name"),
                        filterFaultCode ? faultCode : null, row.getLong("fault_pile_days"),
                        row.getLong("fault_duration_minutes"), row.getLong("affected_order_days")), arguments.toArray());
    }

    /** Returns the minimum multi-evidence input required by a root-cause workflow. */
    public AnomalyEvidence compareAnomalyEvidence(
            String region, String group, LocalDate currentStart, LocalDate currentEnd, LocalDate previousStart, LocalDate previousEnd) {
        return compareAnomalyEvidence(region, null, group, currentStart, currentEnd, previousStart, previousEnd);
    }

    public AnomalyEvidence compareAnomalyEvidence(
            String region, String city, String group, LocalDate currentStart, LocalDate currentEnd, LocalDate previousStart, LocalDate previousEnd) {
        String safeRegion = policy.requiredScope(region, "region");
        regionAccessPolicy.assertAllowed(safeRegion);
        String safeCity = policy.optionalScope(city);
        String safeGroup = policy.requiredScope(group, "group");
        AnalyticsQueryPolicy.DateRange current = policy.dateRange(currentStart, currentEnd);
        AnalyticsQueryPolicy.DateRange previous = policy.dateRange(previousStart, previousEnd);
        return new AnomalyEvidence(
                queryOperationOverview(safeRegion, safeCity, safeGroup, current),
                queryOperationOverview(safeRegion, safeCity, safeGroup, previous),
                queryFaultBreakdown(safeRegion, safeCity, safeGroup, current.startDate(), current.endDate(), 10));
    }

    private OperationOverview queryOperationOverview(String region, String city, String group, AnalyticsQueryPolicy.DateRange range) {
        String safeRegion = policy.requiredScope(region, "region");
        String safeCity = policy.optionalScope(city);
        String groupPredicate = group == null ? "" : " AND group_name = ?";
        String cityPredicate = safeCity == null ? "" : " AND city_name = ?";
        String sql = """
                WITH daily AS (
                    SELECT stat_date, SUM(total_pile_count) AS total_pile_count,
                           SUM(available_pile_count) AS available_pile_count,
                           SUM(offline_pile_count) AS offline_pile_count,
                           SUM(order_count) AS order_count, SUM(success_order_count) AS success_order_count,
                           SUM(energy_kwh) AS energy_kwh, SUM(gmv_amount) AS gmv_amount
                    FROM v_daily_group_operation
                    WHERE region_name = ?%s%s AND stat_date BETWEEN ? AND ?
                    GROUP BY stat_date
                )
                SELECT MAX(stat_date) AS snapshot_date,
                       COALESCE((SELECT latest.available_pile_count FROM daily latest ORDER BY latest.stat_date DESC LIMIT 1), 0)
                           AS available_pile_count,
                       COALESCE(MAX(total_pile_count), 0) AS total_pile_count,
                       COALESCE(ROUND(AVG(available_pile_count), 2), 0) AS avg_available_pile_count,
                       COALESCE(ROUND(AVG(offline_pile_count), 2), 0) AS avg_offline_pile_count,
                       COALESCE(SUM(order_count), 0) AS order_count,
                       COALESCE(SUM(success_order_count), 0) AS success_order_count,
                       COALESCE(SUM(energy_kwh), 0) AS energy_kwh,
                       COALESCE(SUM(gmv_amount), 0) AS gmv_amount,
                       ROUND(CASE WHEN COALESCE(SUM(total_pile_count), 0) = 0 THEN 0
                                  ELSE SUM(offline_pile_count) / SUM(total_pile_count) END, 4) AS offline_rate,
                       ROUND(CASE WHEN COALESCE(SUM(order_count), 0) = 0 THEN 0
                                  ELSE SUM(success_order_count) / SUM(order_count) END, 4) AS share_success_rate
                FROM daily
                """.formatted(cityPredicate, groupPredicate);
        List<Object> arguments = new java.util.ArrayList<>();
        arguments.add(safeRegion);
        if (safeCity != null) arguments.add(safeCity);
        if (group != null) arguments.add(group);
        arguments.add(sqlDate(range.startDate()));
        arguments.add(sqlDate(range.endDate()));
        return jdbcTemplate.queryForObject(sql,
                (row, ignored) -> new OperationOverview(
                        safeRegion, safeCity, group, range.startDate(), range.endDate(), row.getObject("snapshot_date", LocalDate.class),
                        row.getLong("available_pile_count"), row.getLong("total_pile_count"),
                        row.getBigDecimal("avg_available_pile_count"), row.getBigDecimal("avg_offline_pile_count"), row.getLong("order_count"),
                        row.getLong("success_order_count"), row.getBigDecimal("energy_kwh"), row.getBigDecimal("gmv_amount"),
                        row.getBigDecimal("offline_rate"), row.getBigDecimal("share_success_rate")),
                arguments.toArray());
    }

    private Date sqlDate(LocalDate date) {
        return Date.valueOf(date);
    }

    public enum TrendMetric {
        GMV_AMOUNT("SUM(gmv_amount)"), ENERGY_KWH("SUM(energy_kwh)"), ORDER_COUNT("SUM(order_count)"),
        AVAILABLE_PILE_COUNT("SUM(available_pile_count)"), OFFLINE_RATE("ROUND(CASE WHEN SUM(total_pile_count) = 0 THEN 0 ELSE SUM(offline_pile_count) / SUM(total_pile_count) END, 4)");
        private final String selectExpression;
        TrendMetric(String selectExpression) { this.selectExpression = selectExpression; }
        String selectExpression() { return selectExpression; }
    }

    public enum GroupRankingMetric {
        OFFLINE_RATE("offline_rate", "DESC"), GMV_LOW("gmv_amount", "ASC"), FAULT_PILE_DAYS("fault_pile_days", "DESC");
        private final String orderExpression;
        private final String direction;
        GroupRankingMetric(String orderExpression, String direction) { this.orderExpression = orderExpression; this.direction = direction; }
        String orderExpression() { return orderExpression; }
        String direction() { return direction; }
    }

    public record OperationOverview(String region, String city, String group, LocalDate startDate, LocalDate endDate,
            LocalDate snapshotDate, long availablePileCount, long totalPileCount,
            BigDecimal avgAvailablePileCount, BigDecimal avgOfflinePileCount, long orderCount, long successOrderCount,
            BigDecimal energyKwh, BigDecimal gmvAmount, BigDecimal offlineRate, BigDecimal shareSuccessRate) {
        /** Source-compatible constructor for callers that do not request a city-level result. */
        public OperationOverview(String region, String group, LocalDate startDate, LocalDate endDate,
                                 LocalDate snapshotDate, long availablePileCount, long totalPileCount,
                                 BigDecimal avgAvailablePileCount, BigDecimal avgOfflinePileCount, long orderCount,
                                 long successOrderCount, BigDecimal energyKwh, BigDecimal gmvAmount,
                                 BigDecimal offlineRate, BigDecimal shareSuccessRate) {
            this(region, null, group, startDate, endDate, snapshotDate, availablePileCount, totalPileCount,
                    avgAvailablePileCount, avgOfflinePileCount, orderCount, successOrderCount, energyKwh,
                    gmvAmount, offlineRate, shareSuccessRate);
        }
    }
    public record TrendPoint(LocalDate statDate, TrendMetric metric, BigDecimal value) { }
    public record GroupRanking(long groupId, String groupName, GroupRankingMetric rankingMetric, BigDecimal gmvAmount,
            BigDecimal avgAvailablePileCount, BigDecimal avgOfflinePileCount, long faultPileDays, long totalPileCount, BigDecimal offlineRate) { }
    public record FaultBreakdown(String faultType, String faultCode, long faultPileDays, long faultDurationMinutes,
            long affectedOrderDays) { }
    public record FaultGroupRanking(long groupId, String groupName, String faultCode, long faultPileDays,
            long faultDurationMinutes, long affectedOrderDays) { }
    public record AnomalyEvidence(OperationOverview currentPeriod, OperationOverview previousPeriod,
            List<FaultBreakdown> currentFaults) { }
}
