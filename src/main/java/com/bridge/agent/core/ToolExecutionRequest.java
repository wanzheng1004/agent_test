package com.bridge.agent.core;

public record ToolExecutionRequest(
        String runId,
        String toolName,
        String inputJson,
        String scene,
        int stepIndex
) {
}
