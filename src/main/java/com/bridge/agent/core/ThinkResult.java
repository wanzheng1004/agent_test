package com.bridge.agent.core;

/**
 * Parsed result from one LLM thinking turn.
 */
public record ThinkResult(String thought, String action, String actionInput) {

    public boolean isValid() {
        return action != null && !action.isBlank();
    }

    public boolean isTermination() {
        return "FINISH".equals(action) || "ASK_USER".equals(action);
    }
}
