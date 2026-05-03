package com.bridge.agent.agent.during;

import com.bridge.agent.core.AgentContext;
import com.bridge.agent.core.ReActEngine;
import com.bridge.agent.memory.SessionMemoryStore;
import com.bridge.agent.memory.ContextWindowManager;
import com.bridge.agent.memory.dto.NormalizedDefect;
import com.bridge.agent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 检测中 Agent —— 基于 ReAct 模式
 *
 * <p>检测中是三大场景中复杂度最高的场景：
 * <ul>
 *   <li>检测员描述往往不完整，需要多轮追问</li>
 *   <li>规范查询结果会动态揭示需要补充的信息维度</li>
 *   <li>每步工具调用结果都影响下一步决策路径</li>
 * </ul>
 *
 * <p>面试对比点：
 * "检测中选 ReAct 而非 Plan & Execute，是因为任务路径动态：
 *  LLM 先查规范，发现需要补充裂缝宽度信息，然后 ASK_USER 追问，
 *  用户补充后再 normalize_defect 生成记录。
 *  这个路径在事前无法规划，必须每步根据观察结果动态决策。"
 *
 * <p>完成一次规范化后，自动将结果写入 Redis 会话记忆，
 * 供后续检修后 Agent 汇总使用。
 */
@Service
public class DuringInspectionAgent {

    private static final Logger log = LoggerFactory.getLogger(DuringInspectionAgent.class);

    private final ReActEngine reActEngine;
    private final SessionMemoryStore sessionStore;
    private final ContextWindowManager contextWindowManager;
    private final String systemPrompt;

    /** 本 Agent 只开放 2 个工具 —— 工具集越小，LLM 选对的概率越高 */
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "retrieve_standard",
            "normalize_defect"
    );

    public DuringInspectionAgent(ReActEngine reActEngine,
                                   SessionMemoryStore sessionStore,
                                   ContextWindowManager contextWindowManager) {
        this.reActEngine = reActEngine;
        this.sessionStore = sessionStore;
        this.contextWindowManager = contextWindowManager;
        this.systemPrompt = loadPrompt("react-engine.st");
    }

    /**
     * 处理检测中的用户输入
     *
     * @param userMessage 用户当前输入（病害描述或补充信息）
     * @param sessionId   当前检测会话 ID
     * @param bridgeId    当前检测桥梁 ID
     * @return ReAct 执行上下文（含最终回答和执行轨迹）
     */
    public AgentContext chat(String userMessage, String sessionId, String bridgeId) {
        log.info("DuringInspectionAgent processing: sessionId={}, input={}",
                sessionId, userMessage.substring(0, Math.min(50, userMessage.length())));

        // 上下文窗口管理：滑动窗口 + 摘要压缩
        List<Message> context = contextWindowManager.buildContext(sessionId);

        AgentContext result = reActEngine.execute(
                userMessage, context, ALLOWED_TOOLS, systemPrompt,
                sessionId, "DuringInspectionAgent");

        // 如果 ReAct 成功完成且输出包含规范化结果，写入会话记忆
        if (result.getFinalAnswer() != null) {
            tryExtractAndSaveDefect(result.getFinalAnswer(), sessionId);
        }

        return result;
    }

    /**
     * 尝试从 ReAct 最终答案中提取规范化病害记录，写入 Redis 会话记忆。
     * 规范化结果是 JSON 格式，通过字段检测判断是否包含病害记录。
     */
    private void tryExtractAndSaveDefect(String finalAnswer, String sessionId) {
        // normalize_defect 工具的输出是 JSON，包含 component、grade 等字段
        // 从历史步骤的 Observation 中提取（而非直接 parse finalAnswer，finalAnswer 是给用户看的文本）
        // 这里简化处理：如果 finalAnswer 包含 JSON 格式的 grade 字段，则提取
        if (finalAnswer.contains("\"grade\"") && finalAnswer.contains("\"component\"")) {
            try {
                // 提取 JSON 部分
                int start = finalAnswer.indexOf('{');
                int end = finalAnswer.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    String json = finalAnswer.substring(start, end + 1);
                    NormalizedDefect defect = parseDefect(json);
                    if (defect != null && defect.getGrade() != null) {
                        sessionStore.appendDefect(sessionId, defect);
                        log.info("Saved normalized defect to session: component={}, grade={}",
                                defect.getComponent(), defect.getGrade());
                    }
                }
            } catch (Exception e) {
                log.debug("Could not extract defect from final answer: {}", e.getMessage());
            }
        }
    }

    private NormalizedDefect parseDefect(String json) {
        try {
            NormalizedDefect defect = new NormalizedDefect();
            defect.setComponent(JsonUtil.getString(json, "component"));
            defect.setDefectType(JsonUtil.getString(json, "defectType"));
            defect.setDescription(JsonUtil.getString(json, "description"));
            String grade = JsonUtil.getString(json, "grade");
            if (grade != null) {
                defect.setGrade(Integer.parseInt(grade.replaceAll("[^0-9]", "")));
            }
            defect.setStandardRef(JsonUtil.getString(json, "standardRef"));
            defect.setGradeReason(JsonUtil.getString(json, "gradeReason"));
            defect.setUrgency(JsonUtil.getString(json, "urgency"));
            return defect;
        } catch (Exception e) {
            return null;
        }
    }

    private String loadPrompt(String filename) {
        try {
            return new ClassPathResource("prompts/" + filename)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load prompt: {}", filename);
            return "";
        }
    }
}
