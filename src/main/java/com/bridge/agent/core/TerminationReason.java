package com.bridge.agent.core;

/**
 * ReAct Agent 终止原因
 */
public enum TerminationReason {
    NORMAL_FINISH,       // LLM 主动选择 FINISH 动作
    WAITING_USER_INPUT,  // LLM 选择 ASK_USER，等待用户补充
    MAX_ITERATIONS,      // 达到最大循环次数
    CONSECUTIVE_ERRORS,  // 连续错误超限
    USER_CANCELLED       // 用户取消（预留）
}
