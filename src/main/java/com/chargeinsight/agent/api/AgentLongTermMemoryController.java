package com.chargeinsight.agent.api;

import com.chargeinsight.agent.chat.AgentLongTermMemoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Explicit opt-in API: ordinary chat turns never persist a long-term memory by themselves. */
@RestController
@RequestMapping("/api/agent/memories")
public class AgentLongTermMemoryController {
    private final AgentLongTermMemoryService memories;
    public AgentLongTermMemoryController(AgentLongTermMemoryService memories) { this.memories = memories; }

    @GetMapping
    public Map<String, Object> list() { return Map.of("status", "SUCCESS", "result", memories.list()); }

    /** Owner-scoped diagnostic endpoint for verifying semantic recall without exposing another user's memory. */
    @GetMapping("/recall")
    public Map<String, Object> recall(@RequestParam @NotBlank String query,
            @RequestParam(defaultValue = "3") int topK) {
        if (topK < 1 || topK > 10) throw new IllegalArgumentException("topK 应在 1 到 10 之间");
        return Map.of("status", "SUCCESS", "result", memories.recall(query, topK));
    }

    @PostMapping
    public Map<String, Object> remember(@Valid @RequestBody RememberRequest request) {
        return Map.of("status", "SUCCESS", "result", memories.remember(request.content(), request.category()));
    }

    @DeleteMapping("/{memoryId}")
    public Map<String, Object> forget(@PathVariable String memoryId) {
        return Map.of("status", "SUCCESS", "result", memories.forget(memoryId));
    }

    public record RememberRequest(@NotBlank String content, String category) { }
}
