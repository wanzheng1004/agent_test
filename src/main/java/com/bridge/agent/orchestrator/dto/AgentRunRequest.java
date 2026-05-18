package com.bridge.agent.orchestrator.dto;

import java.util.Map;

public record AgentRunRequest(
        String sessionId,
        String userId,
        String message,
        Map<String, Object> metadata
) {
    public ChatRequest toChatRequest() {
        return new ChatRequest(sessionId, userId, message);
    }
}
