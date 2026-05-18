package com.bridge.agent.core;

public enum TerminationReason {
    NORMAL_FINISH,
    WAITING_USER_INPUT,
    HUMAN_APPROVAL_REQUIRED,
    MAX_ITERATIONS,
    CONSECUTIVE_ERRORS,
    USER_CANCELLED,
    TOOL_FAILURE,
    PLAN_FAILED
}
