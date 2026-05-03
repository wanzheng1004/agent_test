package com.bridge.agent.advisor;

import com.bridge.agent.entity.BridgeMemoryEntity;
import com.bridge.agent.memory.BridgeMemoryService;
import com.bridge.agent.memory.SessionMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆注入 Advisor —— 自动将桥梁历史记忆注入 Prompt，并回写会话记忆
 *
 * <p>Spring AI 1.0.0 实现：{@link BaseAdvisor}
 *
 * <p><b>before()</b>：从 request.context() 读取 bridgeId，
 *   查询桥梁级记忆（historical defects + last inspection），
 *   以追加 SystemMessage 的方式注入到 Prompt 中。
 *
 * <p><b>after()</b>：将本轮用户输入和助手回答写入 Redis 会话记忆
 *   （通过 request.context() 中的 sessionId 定位会话）。
 *
 * <p>面试要点：
 * "before/after 各有职责：before 做记忆读取和注入，after 做记忆写回。
 *  通过修改 Prompt 里的 SystemMessage 把历史上下文传给 LLM，
 *  而不是修改 userText，这样不会污染检测员的实际输入内容。"
 *
 * <p>Context 约定（由各 Agent 调用时通过 AdvisorSpec 传入）：
 * <ul>
 *   <li>{@code bridgeId}  — 当前操作桥梁（可选）</li>
 *   <li>{@code sessionId} — 当前会话 ID（可选）</li>
 *   <li>{@code userText}  — 用户原始输入（用于回写记忆，可选）</li>
 * </ul>
 */
@Component
public class MemoryAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(MemoryAdvisor.class);

    private final BridgeMemoryService bridgeMemoryService;
    private final SessionMemoryStore sessionStore;

    // ThreadLocal 保存本次调用的用户输入，供 after() 回写记忆时使用
    private final ThreadLocal<String> userTextHolder = new ThreadLocal<>();

    public MemoryAdvisor(BridgeMemoryService bridgeMemoryService,
                          SessionMemoryStore sessionStore) {
        this.bridgeMemoryService = bridgeMemoryService;
        this.sessionStore = sessionStore;
    }

    @Override
    public String getName() {
        return "MemoryAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1; // LoggingAdvisor 之后
    }

    /**
     * LLM 调用前：注入桥梁历史记忆到 System Prompt
     */
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String bridgeId = (String) request.context().get("bridgeId");
        String sessionId = (String) request.context().get("sessionId");

        // 保存用户输入供 after() 使用
        String userText = extractUserText(request.prompt());
        userTextHolder.set(userText);

        // 注入桥梁级历史记忆
        if (bridgeId != null && !bridgeId.isBlank()) {
            BridgeMemoryEntity memory = bridgeMemoryService.getMemory(bridgeId);
            if (memory != null) {
                String memoryText = bridgeMemoryService.formatMemory(memory);
                Prompt enrichedPrompt = injectMemoryToPrompt(request.prompt(), memoryText);
                log.debug("Injected bridge memory: bridgeId={}, memLen={}",
                        bridgeId, memoryText.length());
                return request.mutate().prompt(enrichedPrompt).build();
            }
        }
        return request;
    }

    /**
     * LLM 调用后：将本轮对话写入 Redis 会话记忆
     */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        String sessionId = (String) response.context().get("sessionId");
        String savedUserText = userTextHolder.get();
        userTextHolder.remove();

        if (sessionId != null && !sessionId.isBlank()) {
            try {
                // 回写用户输入
                if (savedUserText != null && !savedUserText.isBlank()) {
                    sessionStore.appendMessage(sessionId, new UserMessage(savedUserText));
                }
                // 回写助手回答
                String assistantText = response.chatResponse()
                        .getResult().getOutput().getText();
                if (assistantText != null && !assistantText.isBlank()) {
                    sessionStore.appendMessage(sessionId, new AssistantMessage(assistantText));
                }
            } catch (Exception e) {
                log.warn("Failed to write session memory: sessionId={}, err={}",
                        sessionId, e.getMessage());
            }
        }
        return response;
    }

    // ==================== 内部方法 ====================

    /**
     * 将桥梁历史记忆注入 Prompt：
     * 找到已有的 SystemMessage 追加，若无则新增一条。
     */
    private Prompt injectMemoryToPrompt(Prompt prompt, String memoryText) {
        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        String memorySection = "\n\n## 桥梁历史记忆（来自往期检测数据）\n" + memoryText;

        boolean injected = false;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage sm) {
                messages.set(i, new SystemMessage(sm.getText() + memorySection));
                injected = true;
                break;
            }
        }
        if (!injected) {
            messages.add(0, new SystemMessage(memorySection));
        }
        return new Prompt(messages, prompt.getOptions());
    }

    /** 从 Prompt 中提取用户输入文本（最后一条 UserMessage） */
    private String extractUserText(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage um) {
                return um.getText();
            }
        }
        return null;
    }
}
