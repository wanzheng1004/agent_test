package com.bridge.agent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class BgeRerankerClient {

    private static final Logger log = LoggerFactory.getLogger(BgeRerankerClient.class);

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public BgeRerankerClient(
            RestClient.Builder restClientBuilder,
            @Value("${bridge.models.bge.reranker.base-url:http://localhost:8003/v1}") String baseUrl,
            @Value("${bridge.models.bge.reranker.api-key:}") String apiKey,
            @Value("${bridge.models.bge.reranker.model:bge-reranker-v2-m3}") String model) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
    }

    public List<ScoredDoc> rerank(String query, List<ScoredDoc> candidates, int finalTopK) {
        if (candidates == null || candidates.size() <= 1) {
            return candidates;
        }

        Map<String, Object> payload = Map.of(
                "model", model,
                "query", query,
                "documents", candidates.stream().map(ScoredDoc::content).toList(),
                "top_n", Math.min(finalTopK, candidates.size()),
                "return_documents", false
        );

        try {
            JsonNode response = restClient.post()
                    .uri("/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> applyAuth(headers, apiKey))
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode resultArray = response.has("results") ? response.path("results") : response.path("data");
            List<ScoredDoc> ranked = new ArrayList<>();
            resultArray.forEach(item -> {
                int index = item.path("index").asInt(-1);
                double score = item.has("relevance_score")
                        ? item.path("relevance_score").asDouble()
                        : item.path("score").asDouble();
                if (index >= 0 && index < candidates.size()) {
                    ranked.add(candidates.get(index).withScore(score).withSource("RERANK"));
                }
            });

            ranked.sort(Comparator.comparingDouble(ScoredDoc::score).reversed());
            return ranked.isEmpty()
                    ? candidates.subList(0, Math.min(finalTopK, candidates.size()))
                    : ranked.subList(0, Math.min(finalTopK, ranked.size()));
        } catch (Exception e) {
            log.warn("BGE reranking failed, fallback to RRF order: {}", e.getMessage());
            return candidates.subList(0, Math.min(finalTopK, candidates.size()));
        }
    }

    private void applyAuth(HttpHeaders headers, String key) {
        if (StringUtils.hasText(key)) {
            headers.setBearerAuth(key);
        }
    }
}
