package com.bridge.agent.orchestrator;

import com.bridge.agent.orchestrator.dto.IntentResult;
import com.bridge.agent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 意图分类器 —— 单次 LLM 调用（非 Agent 循环）
 *
 * <p>面试要点：
 * "编排器不是 Agent，不用 ReAct 循环。意图分类是单次 LLM 调用，
 *  结果用 Spring AI 结构化输出解析为 IntentResult 对象，
 *  不需要 LLM 循环推理。让 LLM 做需要推理的事，
 *  让代码做确定性的事（槽位校验、路由），各司其职。"
 *
 * <p>使用 Few-shot Prompt（外置 .st 文件）同时完成：
 * <ul>
 *   <li>场景分类（4 类）</li>
 *   <li>关键实体提取（bridgeId、defectDescription、standardRef）</li>
 *   <li>缺失槽位识别</li>
 * </ul>
 */
@Component
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    private final ChatClient chatClient;
    private final String classifyPrompt;

    public IntentClassifier(ChatClient.Builder builder) {
        this.chatClient = builder.build();
        this.classifyPrompt = loadPrompt();
    }

    /**
     * 分类用户意图并提取槽位
     *
     * @param userInput 用户当前输入
     * @param history   最近几轮会话历史（提供补充上下文）
     * @return 意图分类结果（含场景和槽位信息）
     */
    public IntentResult classify(String userInput, List<Message> history) {
        log.debug("Classifying intent: input={}", userInput.substring(0, Math.min(100, userInput.length())));

        try {
            // Spring AI 结构化输出：让 LLM 直接返回符合 IntentResult 格式的 JSON
            String response = chatClient.prompt()
                    .system(classifyPrompt)
                    .messages(history.subList(
                            Math.max(0, history.size() - 4), history.size())) // 最近 2 轮
                    .user(userInput)
                    .call()
                    .content();

            // 提取 JSON 并解析
            String json = extractJson(response);
            IntentResult result = JsonUtil.parse(json, IntentResult.class);

            log.info("Intent classified: scene={}, bridgeId={}, missing={}",
                    result.scene(), result.bridgeId(), result.missingSlots());
            return result;

        } catch (Exception e) {
            log.error("Intent classification failed: {}", e.getMessage());
            // 兜底：无法分类时路由到通用问答
            return new IntentResult(
                    com.bridge.agent.orchestrator.dto.SceneType.GENERAL,
                    null, null, null, List.of());
        }
    }

    private String extractJson(String response) {
        if (response == null) return "{}";
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        return (start >= 0 && end > start) ? response.substring(start, end + 1) : response;
    }

    private String loadPrompt() {
        try {
            return new ClassPathResource("prompts/intent-classifier.st")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load intent-classifier.st");
            return "根据用户输入判断场景（PRE_INSPECTION/DURING_INSPECTION/POST_INSPECTION/GENERAL），输出JSON。";
        }
    }
}
