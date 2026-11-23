package com.opsatlas.rag;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class PolicyVectorIndex {
    record Match(String id, double score) {}

    private final RestClient qdrant;
    private final PolicyEmbedding model;
    private final String collection;

    public PolicyVectorIndex(@Value("${OPSATLAS_QDRANT_URL:http://127.0.0.1:16342}") String url, PolicyEmbedding model) {
        var http = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        http.setReadTimeout(Duration.ofSeconds(8));
        qdrant = RestClient.builder().baseUrl(url).requestFactory(http).build();
        this.model = model;
        collection = "/collections/opsatlas_" + Integer.toUnsignedString(model.profile().hashCode(), 16);
    }

    void ensureCollection() {
        try {
            qdrant.get().uri(collection).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound ignored) {
            qdrant.put().uri(collection).body(Map.of("vectors", Map.of(
                    "size", model.dimensions(), "distance", "Cosine"))).retrieve().toBodilessEntity();
        }
    }

    void index(List<KnowledgeMapper.Chunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        var points = chunks.stream().map(chunk -> Map.of(
                "id", chunk.id(), "vector", model.embed(chunk.title() + " " + chunk.content()))).toList();
        qdrant.put().uri(collection + "/points?wait=true").body(Map.of("points", points))
                .retrieve().toBodilessEntity();
    }

    List<Match> query(String question, int limit) {
        JsonNode response = qdrant.post().uri(collection + "/points/query")
                .body(Map.of("query", model.embed(question), "limit", limit, "score_threshold", 0.12))
                .retrieve().body(JsonNode.class);
        var result = new ArrayList<Match>();
        for (JsonNode point : response.path("result").path("points")) {
            result.add(new Match(point.path("id").asText(), point.path("score").asDouble()));
        }
        return result;
    }

    public Map<String, Object> status() {
        JsonNode response = qdrant.get().uri(collection).retrieve().body(JsonNode.class);
        return Map.of("profile", model.profile(), "points", response.path("result").path("points_count").asLong());
    }
}
