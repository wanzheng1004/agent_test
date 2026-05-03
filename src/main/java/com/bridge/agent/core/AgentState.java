package com.bridge.agent.core;

/**
 * ReAct Agent 执行状态机
 */
public enum AgentState {
    RUNNING,            // 执行中
    FINISHED,           // 正常完成（LLM 输出 FINISH 或 ASK_USER）
    FAILED,             // 异常终止（连续错误超限）
    MAX_ITER_EXCEEDED   // 达到最大循环次数强制终止
}
