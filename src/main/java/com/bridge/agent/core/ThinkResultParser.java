package com.bridge.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM ReAct 格式输出解析器
 *
 * <p>将 LLM 输出的文本解析为结构化的 ThinkResult。
 * 预期格式：
 * <pre>
 * Thought: 分析当前情况...
 * Action: retrieve_standard
 * ActionInput: {"defectQuery": "竖向裂缝 0.3mm"}
 * </pre>
 *
 * <p>面试要点：
 * <ul>
 *   <li>为什么用文本解析而非 Spring AI function calling？
 *       → function calling 由框架自动循环，无法在中间插入质量校验、轨迹记录、纠正机制</li>
 *   <li>解析失败时返回 action=null 的 ThinkResult，触发 self-correction 流程</li>
 * </ul>
 */
@Component
public class ThinkResultParser {

    private static final Logger log = LoggerFactory.getLogger(ThinkResultParser.class);

    // 提取 Thought: 后面直到 Action: 之间的内容
    private static final Pattern THOUGHT_PATTERN =
            Pattern.compile("Thought:\\s*(.+?)(?=\\nAction:)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    // 提取 Action: 后面直到 ActionInput: 之间的内容（单行）
    private static final Pattern ACTION_PATTERN =
            Pattern.compile("Action:\\s*([^\\n]+)", Pattern.CASE_INSENSITIVE);

    // 提取 ActionInput: 后面所有内容
    private static final Pattern INPUT_PATTERN =
            Pattern.compile("ActionInput:\\s*(.+)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * 解析 LLM 输出文本为 ThinkResult。
     * 解析失败时 action 为 null，调用方应触发 self-correction。
     */
    public ThinkResult parse(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            log.warn("LLM output is empty, returning invalid ThinkResult");
            return new ThinkResult("", null, "");
        }

        String thought = extractGroup(THOUGHT_PATTERN, llmOutput);
        String action = extractGroup(ACTION_PATTERN, llmOutput);
        String actionInput = extractGroup(INPUT_PATTERN, llmOutput);

        // 清理 action：去空格、处理特殊动作大小写
        if (action != null) {
            action = action.trim();
            // 允许大小写不敏感匹配特殊动作
            if (action.equalsIgnoreCase("FINISH")) action = "FINISH";
            else if (action.equalsIgnoreCase("ASK_USER")) action = "ASK_USER";
        }

        ThinkResult result = new ThinkResult(
                thought != null ? thought.trim() : "",
                action,
                actionInput != null ? actionInput.trim() : ""
        );

        if (!result.isValid()) {
            log.warn("Failed to parse ThinkResult from output:\n{}", llmOutput);
        } else {
            log.debug("Parsed ThinkResult: action={}, inputLen={}",
                    result.action(), result.actionInput().length());
        }

        return result;
    }

    /** 诊断解析失败的原因，用于 self-correction 提示 */
    public String diagnoseProblem(ThinkResult bad, String rawOutput) {
        if (bad.action() == null || bad.action().isBlank()) {
            if (!rawOutput.contains("Action:")) {
                return "输出中缺少 'Action:' 标记，请严格按 Thought/Action/ActionInput 格式输出";
            }
            return "Action 内容为空，请选择一个工具名称或 FINISH/ASK_USER";
        }
        return "输出格式异常，请重新按照指定格式输出";
    }

    private String extractGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
