package com.chargeinsight.analytics.api;

import com.chargeinsight.analytics.service.DailyAggregationService;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/aggregations")
public class AggregationController {
    private final DailyAggregationService dailyAggregationService;

    public AggregationController(DailyAggregationService dailyAggregationService) {
        this.dailyAggregationService = dailyAggregationService;
    }

    @PostMapping("/daily")
    public Map<String, Object> rebuild(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Map.of("status", "SUCCESS", "result", dailyAggregationService.rebuild(date));
    }
}
