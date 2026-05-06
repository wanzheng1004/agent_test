package com.bridge.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class OpenAiCompatibleChatService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatService.class);

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;

    public OpenAiCompatibleChatService(
            RestClient.Builder restClientBuilder,
            @Value("${bridge.models.qwen.base-url:http://localhost:8001/v1}") String baseUrl,
            @Value("${bridge.models.qwen.api-key:}") String apiKey,
            @Value("${bridge.models.qwen.model:qwen2.5-7b-instruct}") String model,
            @Value("${bridge.models.qwen.temperature:0.1}") double temperature,
            @Value("${bridge.models.qwen.max-tokens:1024}") int maxTokens) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    public String complete(String systemPrompt, String userPrompt) {
        Map<String, Object> request = Map.of(
                "model", model,
                "temperature", temperature,
                "max_tokens", maxTokens,
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        try {
            JsonNode response = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> applyAuth(headers, apiKey))
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode content = response.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new IllegalStateException("Qwen response missing choices[0].message.content");
            }
            return content.asText();
        } catch (Exception e) {
            log.error("Local Qwen call failed: {}", e.getMessage());
            throw e;
        }
    }

    private void applyAuth(HttpHeaders headers, String key) {
        if (StringUtils.hasText(key)) {
            headers.setBearerAuth(key);
        }
    }
}
