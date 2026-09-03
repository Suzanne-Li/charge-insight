package com.chargeinsight.common.api;

import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {
    private final boolean securityEnabled;

    public HealthController(@Value("${charge.security.enabled:false}") boolean securityEnabled) {
        this.securityEnabled = securityEnabled;
    }

    @GetMapping
    public Map<String, Object> health() {
        return Map.of("status", "SUCCESS", "service", "charge-insight-backend", "securityEnabled", securityEnabled,
                "timestamp", Instant.now().toString());
    }
}
