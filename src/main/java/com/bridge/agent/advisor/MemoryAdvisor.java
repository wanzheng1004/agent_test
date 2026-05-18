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

@Component
public class MemoryAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(MemoryAdvisor.class);

    private final BridgeMemoryService bridgeMemoryService;
    private final SessionMemoryStore sessionStore;
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
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String bridgeId = (String) request.context().get("bridgeId");
        userTextHolder.set(extractUserText(request.prompt()));

        if (bridgeId != null && !bridgeId.isBlank()) {
            BridgeMemoryEntity memory = bridgeMemoryService.getMemory(bridgeId);
            if (memory != null) {
                String memoryText = bridgeMemoryService.formatMemory(memory);
                Prompt enrichedPrompt = injectMemoryToPrompt(request.prompt(), memoryText);
                log.debug("Injected bridge memory: bridgeId={}, len={}", bridgeId, memoryText.length());
                return request.mutate().prompt(enrichedPrompt).build();
            }
        }
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        String sessionId = (String) response.context().get("sessionId");
        String savedUserText = userTextHolder.get();
        userTextHolder.remove();

        if (sessionId != null && !sessionId.isBlank()) {
            try {
                if (savedUserText != null && !savedUserText.isBlank()) {
                    sessionStore.appendMessage(sessionId, new UserMessage(savedUserText));
                }
                String assistantText = response.chatResponse().getResult().getOutput().getText();
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

    private Prompt injectMemoryToPrompt(Prompt prompt, String memoryText) {
        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        String memorySection = "\n\n## Bridge Memory\n" + memoryText;

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
