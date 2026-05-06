package com.bridge.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
@Primary
public class LocalBgeEmbeddingModel implements EmbeddingModel {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final int dimensions;

    public LocalBgeEmbeddingModel(
            RestClient.Builder restClientBuilder,
            @Value("${bridge.models.bge.embedding.base-url:http://localhost:8002/v1}") String baseUrl,
            @Value("${bridge.models.bge.embedding.api-key:}") String apiKey,
            @Value("${bridge.models.bge.embedding.model:bge-m3}") String model,
            @Value("${bridge.models.bge.embedding.dimensions:1024}") int dimensions) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        Map<String, Object> payload = Map.of(
                "model", model,
                "input", request.getInstructions()
        );

        JsonNode response = restClient.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuth(headers, apiKey))
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        List<JsonNode> data = new ArrayList<>();
        response.path("data").forEach(data::add);
        data.sort(Comparator.comparingInt(node -> node.path("index").asInt(0)));

        List<Embedding> results = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            JsonNode embeddingNode = data.get(i).path("embedding");
            float[] vector = new float[embeddingNode.size()];
            for (int j = 0; j < embeddingNode.size(); j++) {
                vector[j] = (float) embeddingNode.get(j).asDouble();
            }
            results.add(new Embedding(vector, i));
        }

        return new EmbeddingResponse(results);
    }

    @Override
    public float[] embed(Document document) {
        return call(new EmbeddingRequest(List.of(document.getText()), null))
                .getResult()
                .getOutput();
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private void applyAuth(HttpHeaders headers, String key) {
        if (StringUtils.hasText(key)) {
            headers.setBearerAuth(key);
        }
    }
}
