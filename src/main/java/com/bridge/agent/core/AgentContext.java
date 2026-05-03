package com.bridge.agent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct Agent 完整执行上下文
 *
 * <p>贯穿整个 ReAct 循环的可变状态容器，记录：
 * <ul>
 *   <li>每一步的 Thought-Action-Observation</li>
 *   <li>中间数据暂存（scratchpad）</li>
 *   <li>最终答案和终止原因</li>
 * </ul>
 *
 * <p>面试要点：
 * <ul>
 *   <li>getTrajectoryText() 将历史步骤格式化为文本，注入下一轮 Prompt</li>
 *   <li>scratchpad 用于步骤间传递中间结果（如暂存规范查询结果供后续步骤使用）</li>
 * </ul>
 */
public class AgentContext {

    private final String sessionId;
    private final String agentName;
    private final List<AgentStep> steps = new ArrayList<>();
    private final Map<String, Object> scratchpad = new HashMap<>();

    private AgentState state = AgentState.RUNNING;
    private TerminationReason terminationReason;
    private String finalAnswer;
    private boolean waitingForUser = false; // ASK_USER 后等待下一轮输入

    public AgentContext(String sessionId, String agentName) {
        this.sessionId = sessionId;
        this.agentName = agentName;
    }

    // ==================== 步骤管理 ====================

    public void addStep(AgentStep step) {
        steps.add(step);
    }

    /**
     * 将所有历史步骤格式化为 Prompt 可读文本，注入下一轮 Think 阶段。
     * 这是 ReAct 循环的核心机制：LLM 通过读取轨迹了解已做了什么。
     */
    public String getTrajectoryText() {
        if (steps.isEmpty()) return "（尚无历史步骤，这是第一步）";
        StringBuilder sb = new StringBuilder();
        for (AgentStep step : steps) {
            sb.append(step.toTrajectoryText()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 连续错误计数：统计尾部连续的失败步骤数量。
     * 用于 ReActEngine 判断是否触发终止。
     */
    public int consecutiveErrorCount() {
        int count = 0;
        for (int i = steps.size() - 1; i >= 0; i--) {
            StepStatus s = steps.get(i).status();
            if (s == StepStatus.SUCCESS) break;
            count++;
        }
        return count;
    }

    // ==================== 状态管理 ====================

    public void terminate(AgentState state, TerminationReason reason) {
        this.state = state;
        this.terminationReason = reason;
        this.waitingForUser = (reason == TerminationReason.WAITING_USER_INPUT);
    }

    // ==================== Scratchpad ====================

    public void put(String key, Object value) {
        scratchpad.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) scratchpad.get(key);
    }

    // ==================== Getters ====================

    public String getSessionId()                   { return sessionId; }
    public String getAgentName()                   { return agentName; }
    public List<AgentStep> getSteps()              { return Collections.unmodifiableList(steps); }
    public AgentState getState()                   { return state; }
    public TerminationReason getTerminationReason() { return terminationReason; }
    public String getFinalAnswer()                  { return finalAnswer; }
    public boolean isWaitingForUser()               { return waitingForUser; }
    public boolean isRunning()                      { return state == AgentState.RUNNING; }

    public void setFinalAnswer(String finalAnswer)  { this.finalAnswer = finalAnswer; }
}
