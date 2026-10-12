package com.chargeinsight.agent.api;

import com.chargeinsight.agent.trace.AnalyticsTraceService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/agent/traces")
public class AnalyticsTraceController {
    private final AnalyticsTraceService traceService;

    public AnalyticsTraceController(AnalyticsTraceService traceService) {
        this.traceService = traceService;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) Integer limit) {
        return Map.of("status", "SUCCESS", "traces", traceService.list(limit));
    }

    @GetMapping("/{traceId}")
    public Map<String, Object> get(@PathVariable String traceId) {
        try {
            return Map.of("status", "SUCCESS", "trace", traceService.get(traceId));
        } catch (AnalyticsTraceService.TraceNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Trace 不存在");
        }
    }

    @GetMapping("/correlations/{correlationId}")
    public Map<String, Object> correlation(@PathVariable String correlationId) {
        return Map.of("status", "SUCCESS", "traces", traceService.correlation(correlationId));
    }
}
