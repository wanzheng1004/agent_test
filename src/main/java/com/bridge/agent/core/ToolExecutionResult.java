package com.bridge.agent.core;

public record ToolExecutionResult(
        String toolName,
        String output,
        StepStatus status,
        long latencyMs,
        String approvalId
) {
    public boolean success() {
        return status == StepStatus.SUCCESS;
    }

    public boolean pendingApproval() {
        return status == StepStatus.PENDING_APPROVAL;
    }
}
