package com.chargeinsight.agent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** Qdrant REST adapter. It is only called by the explicit index-rebuild or dense-retrieval paths. */
@Service
public class QdrantKnowledgeVectorStore implements KnowledgeVectorStore {
    private final RestClient client;
    private final boolean enabled;
    private final String collection;

    public QdrantKnowledgeVectorStore(@Value("${charge.vector.enabled:false}") boolean enabled,
            @Value("${charge.vector.qdrant.base-url:http://localhost:6333}") String baseUrl,
            @Value("${charge.vector.qdrant.collection:chargeinsight_knowledge_v1}") String collection) {
        this.enabled = enabled;
        this.collection = collection;
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public void upsert(List<VectorPoint> points, int dimensions) {
        if (!enabled) throw new IllegalStateException("向量索引未启用");
        if (points.isEmpty()) return;
        ensureCollection(dimensions);
        List<Map<String, Object>> qdrantPoints = points.stream().map(point -> Map.of(
                "id", UUID.nameUUIDFromBytes(("knowledge:" + point.id()).getBytes(StandardCharsets.UTF_8)).toString(),
                "vector", point.vector(),
                "payload", Map.of("knowledge_id", point.id(), "metadata", point.payload()))).toList();
        client.put().uri("/collections/{collection}/points?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("points", qdrantPoints))
                .retrieve().toBodilessEntity();
    }

    @Override
    public List<VectorMatch> search(float[] vector, int topK) {
        if (!enabled) return List.of();
        JsonNode response = client.post().uri("/collections/{collection}/points/search", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("vector", vector, "limit", topK, "with_payload", true))
                .retrieve().body(JsonNode.class);
        if (response == null || !response.path("status").asText().equals("ok")) {
            throw new IllegalStateException("Qdrant 检索响应无效");
        }
        List<VectorMatch> matches = new ArrayList<>();
        response.path("result").forEach(result -> matches.add(new VectorMatch(
                result.path("payload").path("knowledge_id").asText(), result.path("score").asDouble())));
        return List.copyOf(matches);
    }

    @Override
    public VectorStoreStatus status() {
        return enabled
                ? new VectorStoreStatus(true, "CONFIGURED", "Qdrant collection=" + collection)
                : new VectorStoreStatus(false, "DISABLED", "CHARGE_VECTOR_ENABLED=false");
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
