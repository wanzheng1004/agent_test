package com.bridge.agent.orchestrator.dto;

import com.bridge.agent.core.ResumeDecision;

public record ResumeRunRequest(
        String action,
        String editedInputJson,
        String comment,
        String userId
) {
    public ResumeDecision toDecision() {
        return new ResumeDecision(action, editedInputJson, comment, userId);
    }
}
