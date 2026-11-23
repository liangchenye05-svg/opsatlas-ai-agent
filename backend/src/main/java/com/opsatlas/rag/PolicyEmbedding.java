package com.opsatlas.rag;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PolicyEmbedding {
    private final String model;
    private final int dimensions;
    private final RestClient provider;

    public PolicyEmbedding(@Value("${OPSATLAS_EMBEDDING_URL:}") String url,
                           @Value("${OPSATLAS_EMBEDDING_API_KEY:}") String key,
                           @Value("${OPSATLAS_EMBEDDING_MODEL:character-bigrams}") String model,
                           @Value("${OPSATLAS_EMBEDDING_DIMENSIONS:384}") int dimensions) {
        if (dimensions < 64 || dimensions > 4096) {
            throw new IllegalArgumentException("Embedding dimensions must be between 64 and 4096");
        }
        this.model = url.isBlank() ? "character-bigrams" : model;
        this.dimensions = dimensions;
        if (url.isBlank()) {
            provider = null;
            return;
        }
        var transport = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        transport.setReadTimeout(Duration.ofSeconds(8));
        var builder = RestClient.builder().requestFactory(transport).baseUrl(url);
        if (!key.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + key);
        }
        provider = builder.build();
    }

    int dimensions() {
        return dimensions;
    }

    String profile() {
        return model + "-" + dimensions;
    }

    float[] embed(String content) {
        if (provider != null) {
            JsonNode body = provider.post().uri("/embeddings").body(Map.of("model", model, "input", content))
                    .retrieve().body(JsonNode.class);
            JsonNode vector = body.path("data").path(0).path("embedding");
            if (vector.size() != dimensions) {
                throw new IllegalStateException("Embedding vector does not match configured dimensions");
            }
            float[] result = new float[dimensions];
            for (int i = 0; i < dimensions; i++) {
                result[i] = vector.get(i).floatValue();
            }
            return result;
        }

        float[] vector = new float[dimensions];
        for (String term : TextFeatures.terms(content)) {
            int hash = term.hashCode();
            vector[Math.floorMod(hash, dimensions)] += (Integer.rotateRight(hash, 9) & 1) == 0 ? 1 : -1;
        }
        return vector;
    }
}
