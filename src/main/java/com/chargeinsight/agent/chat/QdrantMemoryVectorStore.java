package com.chargeinsight.agent.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** Qdrant adapter for the derived, owner-filtered long-term-memory index. */
@Service
public class QdrantMemoryVectorStore implements MemoryVectorStore {
    private final RestClient client;
    private final boolean enabled;
    private final String collection;

    public QdrantMemoryVectorStore(@Value("${charge.vector.enabled:false}") boolean enabled,
            @Value("${charge.vector.qdrant.base-url:http://localhost:6333}") String baseUrl,
            @Value("${charge.vector.qdrant.memory-collection:chargeinsight_memory_v1}") String collection) {
        this.enabled = enabled;
        this.collection = collection;
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public void upsert(List<VectorPoint> points, int dimensions) {
        if (!enabled) throw new IllegalStateException("向量记忆未启用");
        if (points.isEmpty()) return;
        ensureCollection(dimensions);
        List<Map<String, Object>> qdrantPoints = points.stream().map(point -> Map.of(
                "id", UUID.nameUUIDFromBytes(("memory:" + point.memoryId()).getBytes(StandardCharsets.UTF_8)).toString(),
                "vector", point.vector(),
                "payload", Map.of("memory_id", point.memoryId(), "metadata", point.payload()))).toList();
        client.put().uri("/collections/{collection}/points?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("points", qdrantPoints))
                .retrieve().toBodilessEntity();
    }

    @Override
    public List<VectorMatch> search(String ownerUsername, float[] vector, int topK) {
        if (!enabled) return List.of();
        JsonNode response = client.post().uri("/collections/{collection}/points/search", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("vector", vector, "limit", topK, "with_payload", true,
                        "filter", Map.of("must", List.of(Map.of("key", "metadata.owner", "match", Map.of("value", ownerUsername))))))
                .retrieve().body(JsonNode.class);
        if (response == null || !response.path("status").asText().equals("ok")) {
            throw new IllegalStateException("Qdrant 长期记忆检索响应无效");
        }
        List<VectorMatch> matches = new ArrayList<>();
        response.path("result").forEach(result -> matches.add(new VectorMatch(
                result.path("payload").path("memory_id").asText(), result.path("score").asDouble())));
        return List.copyOf(matches);
    }

    @Override
    public void delete(String memoryId) {
        if (!enabled) return;
        String pointId = UUID.nameUUIDFromBytes(("memory:" + memoryId).getBytes(StandardCharsets.UTF_8)).toString();
        client.post().uri("/collections/{collection}/points/delete?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("points", List.of(pointId)))
                .retrieve().toBodilessEntity();
    }

    private void ensureCollection(int dimensions) {
        try {
            client.put().uri("/collections/{collection}", collection).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("vectors", Map.of("size", dimensions, "distance", "Cosine")))
                    .retrieve().toBodilessEntity();
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            if (exception.getStatusCode().value() != 409) throw exception;
        }
    }
}
