package com.bridge.agent.core.plan;

import com.bridge.agent.core.StepStatus;

/**
 * Result of one planned tool step.
 */
public record StepResult(
        PlanStep step,
        String result,
        StepStatus status,
        long latencyMs
) {
    public boolean isSuccess() {
        return status == StepStatus.SUCCESS;
    }

    public String toSynthesisText() {
        String mark = isSuccess() ? "OK" : "FAILED";
        String description = step.description() == null ? "" : step.description();
        return String.format("[%s] %s (%s)%nResult: %s",
                mark, description, step.tool(), result);
    }
}
