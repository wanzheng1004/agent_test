package com.bridge.agent.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * 日志 Advisor —— 记录每次 LLM 调用的关键指标
 *
 * <p>Spring AI 1.0.0 正确实现：实现 {@link BaseAdvisor} 接口，
 * 重写 before/after 模板方法（不是 aroundCall）。
 *
 * <p>面试要点：
 * "Spring AI 1.0.0 的 BaseAdvisor 用模板方法模式，
 *  before() 在 LLM 调用前执行，after() 在 LLM 调用后执行，
 *  框架的 adviseCall() 默认实现负责在 before/after 之间调用 chain.nextCall()。
 *  类比 Spring AOP 的 @Before + @After，但更轻量，不需要 AspectJ。"
 *
 * <p>Context 传递：Agent 通过
 * {@code chatClient.prompt().advisors(spec -> spec.param("agentName", name))}
 * 注入上下文，advisor 通过 {@code request.context().get("agentName")} 读取。
 */
@Component
public class LoggingAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LoggingAdvisor.class);

    // ThreadLocal 记录每次调用的开始时间（before → after 之间）
    private final ThreadLocal<Long> startTimeHolder = new ThreadLocal<>();
    // ThreadLocal 记录 agentName（before 存，after 读）
    private final ThreadLocal<String> agentNameHolder = new ThreadLocal<>();

    @Override
    public String getName() {
        return "LoggingAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE; // 最先执行，包裹整个 Advisor 链
    }

    /**
     * LLM 调用前：记录开始时间和 Agent 名称
     */
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        startTimeHolder.set(System.currentTimeMillis());

        String agentName = (String) request.context().getOrDefault("agentName", "unknown-agent");
        agentNameHolder.set(agentName);

        log.debug("[{}] LLM call started | contextKeys={}",
                agentName, request.context().keySet());
        return request; // 不修改请求，直接透传
    }

    /**
     * LLM 调用后：计算耗时，输出 token 消耗
     */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        long elapsed = System.currentTimeMillis() - startTimeHolder.get();
        String agentName = agentNameHolder.get();

        // 清理 ThreadLocal，防止内存泄漏
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
            // usage 在 mock 或异常情况下可能为 null
            log.info("[{}] LLM call done | {}ms", agentName, elapsed);
        }

        return response; // 不修改响应，直接透传
    }
}
