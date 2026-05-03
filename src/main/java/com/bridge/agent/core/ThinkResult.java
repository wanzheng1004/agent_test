package com.bridge.agent.core;

/**
 * LLM 单次 Think 阶段的解析结果
 *
 * <p>来自对 LLM 输出文本的解析，格式为：
 * <pre>
 * Thought: ...
 * Action: ...
 * ActionInput: ...
 * </pre>
 *
 * @param thought     LLM 的推理过程文本
 * @param action      选择的动作（工具名 / FINISH / ASK_USER / null 表示解析失败）
 * @param actionInput 动作的输入参数或最终答案
 */
public record ThinkResult(String thought, String action, String actionInput) {

    /** 判断是否解析成功（action 不为 null 且不为空） */
    public boolean isValid() {
        return action != null && !action.isBlank();
    }

    /** 是否为终止动作 */
    public boolean isTermination() {
        return "FINISH".equals(action) || "ASK_USER".equals(action);
    }
}
