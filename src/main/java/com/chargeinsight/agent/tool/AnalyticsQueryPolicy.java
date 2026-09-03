package com.chargeinsight.agent.tool;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** Shared guardrails for every read-only analytics query. */
@Component
public class AnalyticsQueryPolicy {
    static final int MAX_RANGE_DAYS = 31;
    static final int MAX_ROWS = 20;

    public DateRange dateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("开始日期和结束日期不能为空");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(startDate, endDate) + 1 > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("单次查询时间范围不能超过 " + MAX_RANGE_DAYS + " 天");
        }
        return new DateRange(startDate, endDate);
    }

    public String requiredScope(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return value.trim();
    }

    public int limit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return 10;
        }
        if (requestedLimit < 1 || requestedLimit > MAX_ROWS) {
            throw new IllegalArgumentException("limit 必须在 1 到 " + MAX_ROWS + " 之间");
        }
        return requestedLimit;
    }

    public record DateRange(LocalDate startDate, LocalDate endDate) { }
}
