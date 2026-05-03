package com.bridge.agent.core.plan;

import com.bridge.agent.core.StepStatus;

/**
 * Plan & Execute 单步执行结果
 *
 * @param step      对应的执行步骤定义
 * @param result    工具返回的结果字符串
 * @param status    执行状态
 * @param latencyMs 耗时（毫秒）
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

    /** 格式化为 Synthesizer Prompt 可读的文本 */
    public String toSynthesisText() {
        String statusMark = isSuccess() ? "✅" : "❌";
        return String.format("%s 【%s】%s\n结果：%s",
                statusMark, step.description(), step.tool(), result);
    }
}
