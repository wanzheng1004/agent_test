package com.bridge.agent.config;

import com.bridge.agent.advisor.GuardrailAdvisor;
import com.bridge.agent.advisor.LoggingAdvisor;
import com.bridge.agent.advisor.MemoryAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring AI ChatClient 配置
 *
 * <p>全局默认 {@link ChatClient.Builder}，挂载三层 Advisor 链：
 * <pre>
 * LoggingAdvisor（最先, HIGHEST_PRECEDENCE）
 *   → MemoryAdvisor（HIGHEST_PRECEDENCE + 1）
 *   → 实际 LLM 调用
 *   → GuardrailAdvisor（最后, LOWEST_PRECEDENCE）
 * </pre>
 *
 * <p>各组件通过构造器注入 {@link ChatClient.Builder}，
 * 从而继承全局 Advisor 链，无需在每个 Agent 里重复配置。
 *
 * <p>面试要点：
 * "三个 Advisor 组成完整的横切关注点链（类比 Spring Security 的 FilterChain）：
 *  Logging 计时，Memory 注入上下文，Guardrail 检查输出。
 *  getOrder() 控制顺序，职责分离，互不干扰，新增 Advisor 时不需要修改 Agent 代码。"
 */
@Configuration
public class AiConfig {

    /**
     * 全局默认 ChatClient.Builder（带完整 Advisor 链）
     *
     * <p>注意：各 Agent 和 Engine 通过构造器注入此 Builder，
     * 调用 {@code builder.build()} 得到带 Advisor 的 ChatClient。
     */
    @Bean
    @Primary
    public ChatClient.Builder chatClientBuilder(
            ChatModel chatModel,
            LoggingAdvisor loggingAdvisor,
            MemoryAdvisor memoryAdvisor,
            GuardrailAdvisor guardrailAdvisor) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(loggingAdvisor, memoryAdvisor, guardrailAdvisor);
    }
}
