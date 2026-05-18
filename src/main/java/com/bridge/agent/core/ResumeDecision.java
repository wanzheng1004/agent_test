package com.bridge.agent.core;

public record ResumeDecision(
        String action,
        String editedInputJson,
        String comment,
        String userId
) {
    public boolean approve() {
        return "approve".equalsIgnoreCase(action);
    }

    public boolean reject() {
        return "reject".equalsIgnoreCase(action);
    }

    public boolean edit() {
        return "edit".equalsIgnoreCase(action);
    }
}
