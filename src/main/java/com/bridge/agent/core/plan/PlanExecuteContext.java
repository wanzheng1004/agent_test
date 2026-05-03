package com.bridge.agent.core.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Plan & Execute 完整执行上下文
 *
 * <p>记录：
 * <ul>
 *   <li>原始 Plan（Replan 前）和当前 Plan</li>
 *   <li>每个步骤的执行结果（线程安全，支持并行阶段）</li>
 *   <li>步骤结果 outputKey 映射（用于步骤间数据引用）</li>
 *   <li>是否触发了 Replan，以及 Replan 原因</li>
 * </ul>
 */
public class PlanExecuteContext {

    private final String originalInput;
    private ExecutionPlan originalPlan;
    private ExecutionPlan currentPlan;

    // CopyOnWriteArrayList 支持并行阶段的并发写入
    private final List<StepResult> stepResults = new CopyOnWriteArrayList<>();

    // outputKey → 结果文本 映射，支持步骤间数据引用（${key}）
    private final Map<String, String> outputKeyMap = new HashMap<>();

    private boolean replanned = false;
    private String replanReason;
    private String finalOutput;

    public PlanExecuteContext(String originalInput) {
        this.originalInput = originalInput;
    }

    // ==================== Plan 管理 ====================

    public void setPlan(ExecutionPlan plan) {
        if (this.originalPlan == null) {
            this.originalPlan = plan; // 保存原始 Plan，用于对比
        }
        this.currentPlan = plan;
    }

    public void markReplanned(String reason) {
        this.replanned = true;
        this.replanReason = reason;
    }

    // ==================== 步骤结果管理 ====================

    public void addStepResult(StepResult result) {
        stepResults.add(result);
        if (result.step().outputKey() != null && result.isSuccess()) {
            outputKeyMap.put(result.step().outputKey(), result.result());
        }
    }

    /**
     * 解析步骤输入中的占位符引用：如 ${step_1a} 替换为对应步骤的输出。
     * 支持步骤间的数据流动，不需要 LLM 重新规划。
     */
    public String resolveReference(String input) {
        if (input == null) return null;
        String resolved = input;
        for (Map.Entry<String, String> entry : outputKeyMap.entrySet()) {
            resolved = resolved.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return resolved;
    }

    /**
     * 将所有步骤结果格式化为 Synthesizer Prompt 可读文本
     */
    public String toSynthesisInput() {
        return stepResults.stream()
                .map(StepResult::toSynthesisText)
                .collect(Collectors.joining("\n\n"));
    }

    // ==================== Getters ====================

    public String getOriginalInput()               { return originalInput; }
    public ExecutionPlan getCurrentPlan()          { return currentPlan; }
    public ExecutionPlan getOriginalPlan()         { return originalPlan; }
    public List<StepResult> getStepResults()       { return Collections.unmodifiableList(stepResults); }
    public boolean isReplanned()                   { return replanned; }
    public String getReplanReason()                { return replanReason; }
    public String getFinalOutput()                 { return finalOutput; }
    public Map<String, String> getOutputKeyMap()   { return Collections.unmodifiableMap(outputKeyMap); }

    public void setFinalOutput(String finalOutput) { this.finalOutput = finalOutput; }

    /** 获取指定 outputKey 对应的步骤结果 */
    public String getResultByKey(String key) {
        return outputKeyMap.getOrDefault(key, "");
    }
}
