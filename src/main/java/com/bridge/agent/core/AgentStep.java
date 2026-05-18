package com.bridge.agent.core;

/**
 * One observable Thought-Action-Observation step in a ReAct run.
 */
public record AgentStep(
        int stepIndex,
        String thought,
        String action,
        String actionInput,
        String observation,
        long latencyMs,
        StepStatus status
) {

    public String toTrajectoryText() {
        return String.format(
                "Step %d:%n  Thought: %s%n  Action: %s%n  ActionInput: %s%n  Observation: %s%n",
                stepIndex, thought, action, actionInput, observation);
    }
}
