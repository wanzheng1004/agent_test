package com.bridge.agent.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

@Component
public class LoggingAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LoggingAdvisor.class);

    private final ThreadLocal<Long> startTimeHolder = new ThreadLocal<>();
    private final ThreadLocal<String> agentNameHolder = new ThreadLocal<>();

    @Override
    public String getName() {
        return "LoggingAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        startTimeHolder.set(System.currentTimeMillis());
        String agentName = (String) request.context().getOrDefault("agentName", "unknown-agent");
        agentNameHolder.set(agentName);
        log.debug("[{}] LLM call started | contextKeys={}", agentName, request.context().keySet());
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        Long start = startTimeHolder.get();
        long elapsed = start == null ? 0 : System.currentTimeMillis() - start;
        String agentName = agentNameHolder.get();
        startTimeHolder.remove();
        agentNameHolder.remove();

        try {
            var usage = response.chatResponse().getMetadata().getUsage();
            log.info("[{}] LLM call done | {}ms | prompt={} completion={} total={} tokens",
                    agentName, elapsed,
                    usage.getPromptTokens(),
                    usage.getCompletionTokens(),
                    usage.getTotalTokens());
        } catch (Exception e) {
            log.info("[{}] LLM call done | {}ms", agentName, elapsed);
        }
        return response;
    }
}
