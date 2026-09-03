package com.chargeinsight.agent.api;

import com.chargeinsight.agent.chat.AgentChatSessionService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/sessions")
public class AgentChatSessionController {
    private final AgentChatSessionService sessions;

    public AgentChatSessionController(AgentChatSessionService sessions) { this.sessions = sessions; }

    @GetMapping
    public Map<String, Object> list() { return Map.of("status", "SUCCESS", "result", sessions.list()); }

    @GetMapping("/{sessionId}/messages")
    public Map<String, Object> messages(@PathVariable String sessionId) {
        return Map.of("status", "SUCCESS", "result", sessions.messages(sessionId));
    }

    @DeleteMapping("/{sessionId}")
    public Map<String, Object> delete(@PathVariable String sessionId) {
        sessions.delete(sessionId);
        return Map.of("status", "SUCCESS", "result", List.of());
    }
}
