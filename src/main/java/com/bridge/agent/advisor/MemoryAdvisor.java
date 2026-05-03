package com.bridge.agent.advisor;

import com.bridge.agent.entity.BridgeMemoryEntity;
import com.bridge.agent.memory.BridgeMemoryService;
import com.bridge.agent.memory.SessionMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * 记忆注入 Advisor —— 自动将桥梁历史记忆注入系统提示
 *
 * <p>Before: 从 advisorContext 获取 bridgeId，
 *   查询桥梁级记忆（历次检测摘要、追踪病害），
 *   拼接到系统提示末尾，让 LLM 了解桥梁历史背景。
 *
 * <p>After: 将本轮对话（用户问题 + 助手回答）写入 Redis 会话记忆。
 *
 * <p>面试要点：
 * "记忆注入是所有 Agent 都需要的横切关注点，用 Advisor 避免了
 *  在每个 Agent 里重复写记忆管理代码。
 *  aroundCall 的 before/after 分别处理读取和写入，
 *  职责清晰，类比 AOP 的 @Before/@After。"
 */
@Component
public class MemoryAdvisor implements CallAroundAdvisor {

    private static final Logger log = LoggerFactory.getLogger(MemoryAdvisor.class);

    private final BridgeMemoryService bridgeMemoryService;
    private final SessionMemoryStore sessionMemoryStore;

    public MemoryAdvisor(BridgeMemoryService bridgeMemoryService,
                          SessionMemoryStore sessionMemoryStore) {
        this.bridgeMemoryService = bridgeMemoryService;
        this.sessionMemoryStore = sessionMemoryStore;
    }

    @Override
    public String getName() {
        return "MemoryAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1; // 仅次于 LoggingAdvisor
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest,
                                       CallAroundAdvisorChain chain) {
        String bridgeId   = (String) advisedRequest.adviseContext().get("bridgeId");
        String sessionId  = (String) advisedRequest.adviseContext().get("sessionId");

        // ============ Before: 注入桥梁级历史记忆 ============
        AdvisedRequest enriched = advisedRequest;
        if (bridgeId != null && !bridgeId.isBlank()) {
            BridgeMemoryEntity memory = bridgeMemoryService.getMemory(bridgeId);
            if (memory != null) {
                String memoryContext = bridgeMemoryService.formatMemory(memory);
                String enrichedSystem = (advisedRequest.systemText() != null
                        ? advisedRequest.systemText() : "")
                        + "\n\n## 桥梁历史记忆（来自往期检测）\n" + memoryContext;

                enriched = advisedRequest.mutate()
                        .systemText(enrichedSystem)
                        .build();

                log.debug("Injected bridge memory for bridgeId={}, memoryLen={}",
                        bridgeId, memoryContext.length());
            }
        }

        // ============ 执行 LLM 调用 ============
        AdvisedResponse response = chain.nextAroundCall(enriched);

        // ============ After: 写入会话级记忆 ============
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                // 写入用户输入
                if (advisedRequest.userText() != null && !advisedRequest.userText().isBlank()) {
                    sessionMemoryStore.appendMessage(sessionId,
                            new UserMessage(advisedRequest.userText()));
                }
                // 写入助手回答
                String responseText = response.response().getResult().getOutput().getText();
                if (responseText != null && !responseText.isBlank()) {
                    sessionMemoryStore.appendMessage(sessionId,
                            new AssistantMessage(responseText));
                }
            } catch (Exception e) {
                log.warn("Failed to write session memory for session={}: {}", sessionId, e.getMessage());
            }
        }

        return response;
    }
}
