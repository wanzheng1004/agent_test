package com.bridge.agent.core;

/**
 * Agent 单步执行状态
 */
public enum StepStatus {
    SUCCESS,            // 工具正常返回
    TOOL_ERROR,         // 工具执行异常（DB超时、网络异常等）
    INVALID_ACTION,     // LLM 选了不存在的工具，或参数格式错误
    THOUGHT_LOW_QUALITY // 推理质量校验不通过（无有效 Action）
}
