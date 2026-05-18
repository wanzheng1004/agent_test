package com.bridge.agent.advisor;

import java.util.List;

public record GuardrailDecision(
        GuardrailCheckType type,
        boolean allowed,
        boolean requiresHumanReview,
        List<String> warnings
) {
    public static GuardrailDecision allow(GuardrailCheckType type) {
        return new GuardrailDecision(type, true, false, List.of());
    }

    public static GuardrailDecision warn(GuardrailCheckType type, List<String> warnings) {
        return new GuardrailDecision(type, true, false, warnings);
    }
}
