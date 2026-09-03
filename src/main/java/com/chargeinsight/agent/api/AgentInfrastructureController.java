package com.chargeinsight.agent.api;

import com.chargeinsight.agent.chat.AgentShortTermMemoryService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/infrastructure")
public class AgentInfrastructureController {
    private final AgentShortTermMemoryService shortTermMemory;
    public AgentInfrastructureController(AgentShortTermMemoryService shortTermMemory) {
        this.shortTermMemory = shortTermMemory;
    }

    @GetMapping
    public Map<String, Object> status() {
        return Map.of("status", "SUCCESS", "redis", shortTermMemory.status());
    }
}
