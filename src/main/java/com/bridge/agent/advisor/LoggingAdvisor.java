package com.bridge.agent.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * 日志 Advisor —— 记录每次 LLM 调用的关键指标
 *
 * <p>通过实现 Spring AI 的 CallAroundAdvisor 接口，
 * 在 LLM 调用前后插入日志逻辑，类似 AOP 的 @Around。
 *
 * <p>面试要点：
 * "Advisor 是 Spring AI 的横切关注点机制，类比 Spring AOP 的 @Around。
 *  每个 Agent 的 ChatClient 都挂载了 LoggingAdvisor，
 *  不需要在每个 Agent 里重复写日志代码。"
 *
 * <p>记录内容：
 * <ul>
 *   <li>Agent 名称（来自 advisorContext）</li>
 *   <li>系统提示 token 数（估算）</li>
 *   <li>LLM 调用耗时</li>
 *   <li>Prompt/Completion/Total token 消耗</li>
 * </ul>
 */
@Component
public class LoggingAdvisor implements CallAroundAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LoggingAdvisor.class);

    @Override
    public String getName() {
        return "LoggingAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE; // 最先执行，包裹整个调用链
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest,
                                       CallAroundAdvisorChain chain) {
        long start = System.currentTimeMillis();

        // 从 advisorContext 中获取 Agent 名称（由 Agent 调用时设置）
        String agentName = (String) advisedRequest.adviseContext()
                .getOrDefault("agentName", "unknown-agent");
        String scene = (String) advisedRequest.adviseContext()
                .getOrDefault("scene", "");

        log.debug("[{}{}] LLM call started, systemPromptLen={}",
                agentName, scene.isBlank() ? "" : "/" + scene,
                advisedRequest.systemText() != null ? advisedRequest.systemText().length() : 0);

        // 执行实际 LLM 调用
        AdvisedResponse response = chain.nextAroundCall(advisedRequest);

        long elapsed = System.currentTimeMillis() - start;

        // 提取 token 使用量
        try {
            Usage usage = response.response().getMetadata().getUsage();
            log.info("[{}] LLM call completed | {}ms | prompt={} completion={} total={} tokens",
                    agentName, elapsed,
                    usage.getPromptTokens(),
                    usage.getGenerationTokens(),
                    usage.getTotalTokens());
        } catch (Exception e) {
            // usage 可能为 null（测试 mock 时）
            log.info("[{}] LLM call completed | {}ms", agentName, elapsed);
        }

        return response;
    }
}
