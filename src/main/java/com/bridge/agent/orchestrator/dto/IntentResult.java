package com.bridge.agent.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record IntentResult(
        @JsonProperty("scene") SceneType scene,
        @JsonProperty("bridgeId") String bridgeId,
        @JsonProperty("defectDescription") String defectDescription,
        @JsonProperty("standardRef") String standardRef,
        @JsonProperty("missingSlots") List<String> missingSlots
) {
    public boolean isComplete() {
        return missingSlots == null || missingSlots.isEmpty();
    }
}
