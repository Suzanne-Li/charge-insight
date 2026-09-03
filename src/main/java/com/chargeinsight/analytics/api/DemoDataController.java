package com.chargeinsight.analytics.api;

import com.chargeinsight.analytics.service.DemoDataSeedService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/demo-data")
public class DemoDataController {
    private final DemoDataSeedService demoDataSeedService;

    public DemoDataController(DemoDataSeedService demoDataSeedService) {
        this.demoDataSeedService = demoDataSeedService;
    }

    @PostMapping("/init")
    public Map<String, Object> initialize(@RequestParam(defaultValue = "60") int days) {
        if (days < 7 || days > 180) {
            throw new IllegalArgumentException("days must be between 7 and 180");
        }
        DemoDataSeedService.SeedResult result = demoDataSeedService.initialize(days);
        return Map.of("status", "SUCCESS", "result", result);
    }
}
