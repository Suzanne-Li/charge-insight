package com.chargeinsight.agent.api;

import com.chargeinsight.agent.knowledge.DenseKnowledgeRetriever;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Privileged, explicit lifecycle endpoint; vector indexing never happens during application startup. */
@RestController
@RequestMapping("/api/admin/knowledge-index")
public class KnowledgeIndexController {
    private final DenseKnowledgeRetriever denseRetriever;

    public KnowledgeIndexController(DenseKnowledgeRetriever denseRetriever) {
        this.denseRetriever = denseRetriever;
    }

    @GetMapping
    public Map<String, Object> status() {
        return Map.of("status", "SUCCESS", "result", denseRetriever.status());
    }

    @PostMapping("/rebuild")
    public Map<String, Object> rebuild() {
        return Map.of("status", "SUCCESS", "result", denseRetriever.rebuildIndex());
    }
}
