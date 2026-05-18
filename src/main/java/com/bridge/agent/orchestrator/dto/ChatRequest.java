package com.bridge.agent.orchestrator.dto;

public record ChatRequest(
        String sessionId,
        String userId,
        String message
) {
}
