package com.bridge.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 上下文窗口管理器 —— 滑动窗口 + 摘要压缩
 *
 * <p>问题背景：检测中场景一次完整检测可能产生 50+ 轮对话，
 * 直接全量传入会超出 Token 限制，且早期信息权重被稀释。
 *
 * <p>解决方案：
 * <ul>
 *   <li>保留最近 {@code fullWindowSize} 轮全文</li>
 *   <li>超过 {@code summaryThreshold} 轮时，将前段历史压缩为摘要</li>
 *   <li>摘要注入为 SystemMessage，保证关键信息不丢失</li>
 * </ul>
 *
 * <p>面试要点：
 * "摘要 Prompt 明确指示 LLM 必须保留：桥梁编号、已确认病害列表、
 *  未解决的追问项。这三类信息是检测中场景的关键上下文，
 *  普通摘要会把它们当噪声丢弃。"
 */
@Component
public class ContextWindowManager {

    private static final Logger log = LoggerFactory.getLogger(ContextWindowManager.class);

    private final SessionMemoryStore sessionStore;
    private final ChatClient chatClient;
    private final String summaryPrompt;

    @Value("${bridge.agent.context.full-window-size:20}")
    private int fullWindowSize;

    @Value("${bridge.agent.context.summary-threshold:30}")
    private int summaryThreshold;

    public ContextWindowManager(SessionMemoryStore sessionStore,
                                  ChatClient.Builder builder) {
        this.sessionStore = sessionStore;
        this.chatClient = builder.build();
        this.summaryPrompt = loadPrompt();
    }

    /**
     * 构建注入 Agent 的上下文消息列表。
     *
     * <p>当历史超过 summaryThreshold 轮时，
     * 将前段历史压缩为摘要 SystemMessage，避免 Token 爆炸。
     */
    public List<Message> buildContext(String sessionId) {
        long totalSize = sessionStore.getHistorySize(sessionId);

        if (totalSize <= fullWindowSize) {
            // 历史较短，直接返回全部
            return sessionStore.getHistory(sessionId);
        }

        if (totalSize > summaryThreshold) {
            // 历史较长，触发压缩摘要
            log.info("Context window exceeded threshold ({} > {}), compressing session={}",
                    totalSize, summaryThreshold, sessionId);
            return buildCompressedContext(sessionId, (int) totalSize);
        }

        // 介于 fullWindowSize 和 summaryThreshold 之间：滑动窗口
        int start = (int) (totalSize - fullWindowSize);
        return sessionStore.getHistory(sessionId, start, -1);
    }

    /**
     * 压缩上下文：将前段历史摘要化，保留最近 N 轮全文
     */
    private List<Message> buildCompressedContext(String sessionId, int totalSize) {
        // 获取需要被摘要的历史（全部 - 最近 N 轮）
        int cutoff = totalSize - fullWindowSize;
        List<Message> oldMessages = sessionStore.getHistory(sessionId, 0, cutoff - 1);

        // 调用 LLM 生成摘要
        String historyText = oldMessages.stream()
                .map(m -> (m instanceof org.springframework.ai.chat.messages.UserMessage ? "用户：" : "助手：")
                        + m.getText())
                .collect(Collectors.joining("\n"));

        String summary;
        try {
            summary = chatClient.prompt()
                    .system(summaryPrompt.replace("{conversationHistory}", historyText))
                    .user("请生成摘要。")
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("Context compression failed, using raw truncation: {}", e.getMessage());
            summary = "（历史对话已压缩，部分细节不可用）";
        }

        // 构建压缩后的消息列表
        List<Message> result = new ArrayList<>();
        result.add(new SystemMessage("## 历史对话摘要（已压缩）\n" + summary));
        result.addAll(sessionStore.getHistory(sessionId, cutoff, -1));

        return result;
    }

    private String loadPrompt() {
        try {
            return new ClassPathResource("prompts/context-summary.st")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "请将以下对话历史压缩为简洁摘要，重点保留：桥梁编号、已确认病害、未解决问题。\n对话历史：{conversationHistory}";
        }
    }
}
