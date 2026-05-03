package com.bridge.agent.core;

/**
 * ReAct 的一个完整 Thought-Action-Observation 三元组
 *
 * <p>面试要点：每一轮循环产生一个 AgentStep，通过 getTrajectoryText() 拼接
 * 历史步骤注入到下一轮 Prompt，形成显式的 ReAct 循环。
 */
public record AgentStep(
        int stepIndex,         // 第几步（从 0 开始）
        String thought,        // LLM 的推理过程（Thought: 后的内容）
        String action,         // 选择的动作（工具名 / FINISH / ASK_USER）
        String actionInput,    // 工具入参 JSON 或最终答案文本
        String observation,    // 工具执行返回结果
        long latencyMs,        // 本步耗时（毫秒）
        StepStatus status      // 执行状态
) {

    /** 格式化为 Prompt 可读的文本，注入下一轮 Thought */
    public String toTrajectoryText() {
        return String.format(
                "Step %d:\n  Thought: %s\n  Action: %s\n  ActionInput: %s\n  Observation: %s\n",
                stepIndex, thought, action, actionInput, observation);
    }
}
