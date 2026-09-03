package com.chargeinsight.agent.tool;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Resolves planner time phrases against the newest date actually available in the analytics view. */
@Component
public class AnalysisPeriodResolver {
    private static final Pattern ISO_DATE_RANGE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\s*(?:至|到|to|TO|-|,|，|_)\\s*(\\d{4}-\\d{2}-\\d{2})");
    private final JdbcTemplate jdbcTemplate;
    private final AnalyticsQueryPolicy policy;

    public AnalysisPeriodResolver(JdbcTemplate jdbcTemplate, AnalyticsQueryPolicy policy) {
        this.jdbcTemplate = jdbcTemplate;
        this.policy = policy;
    }

    public ResolvedPeriod resolve(String timeRange) {
        if (timeRange == null || timeRange.isBlank()) {
            throw new IllegalArgumentException("规划结果缺少可解析的 timeRange");
        }
        Matcher matcher = ISO_DATE_RANGE.matcher(timeRange);
        if (matcher.find()) {
            AnalyticsQueryPolicy.DateRange range = policy.dateRange(
                    LocalDate.parse(matcher.group(1)), LocalDate.parse(matcher.group(2)));
            return new ResolvedPeriod(range.startDate(), range.endDate(), "EXPLICIT_DATE_RANGE");
        }
        String normalizedRange = timeRange.trim().toLowerCase(Locale.ROOT);
        if (timeRange.contains("最近一周") || timeRange.contains("近7天") || timeRange.contains("最近7天")
                || normalizedRange.equals("last_7_days")) {
            LocalDate latestDate = jdbcTemplate.queryForObject(
                    "SELECT MAX(stat_date) FROM v_daily_group_operation", LocalDate.class);
            if (latestDate == null) {
                throw new IllegalStateException("运营日汇总视图没有可用于分析的数据");
            }
            return new ResolvedPeriod(latestDate.minusDays(6), latestDate, "LATEST_7_DAYS");
        }
        throw new IllegalArgumentException("暂不支持的 timeRange：" + timeRange + "；请使用最近一周或 YYYY-MM-DD 至 YYYY-MM-DD");
    }

    public ResolvedPeriod previousPeriod(ResolvedPeriod period) {
        long days = ChronoUnit.DAYS.between(period.startDate(), period.endDate()) + 1;
        LocalDate previousEnd = period.startDate().minusDays(1);
        return new ResolvedPeriod(previousEnd.minusDays(days - 1), previousEnd, "PREVIOUS_PERIOD");
    }

    public record ResolvedPeriod(LocalDate startDate, LocalDate endDate, String source) { }
}
