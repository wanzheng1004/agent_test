package com.bridge.agent.memory;

import com.bridge.agent.orchestrator.dto.SceneType;

import java.util.List;
import java.util.Map;

public record MemoryContext(
        String sessionId,
        String bridgeId,
        String userId,
        SceneType scene,
        String shortTermSummary,
        String bridgeLongTermMemory,
        Map<String, Object> userPreferences,
        List<String> injectedSections
) {
}
