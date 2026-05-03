package com.bridge.agent.config;

import com.bridge.agent.advisor.GuardrailAdvisor;
import com.bridge.agent.advisor.LoggingAdvisor;
import com.bridge.agent.advisor.MemoryAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring AI 配置 —— 全局默认 ChatClient（带完整 Advisor 链）
 *
 * <p>ChatClient 通过 ChatClient.Builder 构建，Builder 由 Spring AI 自动注入
 * （基于 application.yml 中的 spring.ai.openai.* 配置）。
 *
 * <p>Advisor 链执行顺序（由 getOrder() 决定）：
 * <pre>
 * LoggingAdvisor（最先）→ MemoryAdvisor → GuardrailAdvisor（最后）
 * </pre>
 *
 * <p>面试要点：
 * "三个 Advisor 组成横切关注点链，类比 Spring AOP 的 @Aspect。
 *  LoggingAdvisor 记录每次 LLM 调用的 token 消耗和延迟；
 *  MemoryAdvisor 在调用前注入桥梁历史记忆，调用后写入会话记忆；
 *  GuardrailAdvisor 在调用后检查输出是否符合规范要求。
 *  三者职责分离，通过 getOrder() 控制顺序，互不干扰。"
 */
@Configuration
public class AiConfig {

    /**
     * 全局默认 ChatClient（所有 Agent 共用，注入 Advisor 链）
     *
     * <p>各 Agent 在构造时通过 ChatClient.Builder 注入此 Bean，
     * 从而自动继承 Advisor 链。
     */
    @Bean
    @Primary
    public ChatClient.Builder chatClientBuilder(
            org.springframework.ai.chat.model.ChatModel chatModel,
            LoggingAdvisor loggingAdvisor,
            MemoryAdvisor memoryAdvisor,
            GuardrailAdvisor guardrailAdvisor) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(loggingAdvisor, memoryAdvisor, guardrailAdvisor);
    }
}
