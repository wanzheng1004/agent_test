package com.bridge.agent.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 安全护栏 Advisor —— 检查 LLM 输出是否包含超出规范范围的建议
 *
 * <p>Spring AI 1.0.0 实现：{@link BaseAdvisor}，只在 after() 阶段做输出校验。
 *
 * <p>策略：当前用关键词黑名单规则（快速落地）。
 * 因为实现了 BaseAdvisor 接口，后续可以无缝替换为 LLM-as-judge
 * 而不影响任何 Agent 代码——这正是 Advisor 模式的价值所在。
 *
 * <p>面试要点：
 * "Guardrail 只在 after() 里做，before() 直接透传不处理。
 *  当前是关键词规则，但接口不变：若要升级成 LLM-as-judge，
 *  只需改 after() 方法里的逻辑，外部调用方零修改。
 *  这是开闭原则在 Advisor 模式里的具体体现。"
 */
@Component
public class GuardrailAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(GuardrailAdvisor.class);

    /**
     * 处置建议中不应出现的绝对化风险词。
     * Agent 应给出基于规范的建议，不应单方面做封路、拆除等行政决策。
     */
    private static final List<String> RISKY_PATTERNS = List.of(
            "立即拆除",   // 拆除决策需专业机构鉴定
            "禁止通行",   // 封路需行政流程
            "危桥",       // 危桥认定需专项鉴定
            "必须立刻"    // 过度绝对化表述
    );

    @Override
    public String getName() {
        return "GuardrailAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE; // 最后执行，检查最终输出
    }

    /**
     * before()：直接透传，不做任何处理
     */
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    /**
     * after()：校验输出是否合规
     *
     * <p>当前只对 POST_INSPECTION 场景（处置建议）做严格校验。
     * 通过 context 中的 "scene" 字段判断当前场景。
     */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        String scene = (String) response.context().getOrDefault("scene", "");

        if ("POST_INSPECTION".equals(scene)) {
            try {
                String output = response.chatResponse().getResult().getOutput().getText();
                checkOutput(output, scene);
            } catch (Exception e) {
                log.debug("Guardrail check skipped: {}", e.getMessage());
            }
        }
        return response;
    }

    private void checkOutput(String output, String scene) {
        if (output == null || output.isBlank()) return;

        // 检查风险词
        for (String pattern : RISKY_PATTERNS) {
            if (output.contains(pattern)) {
                log.warn("[Guardrail][{}] Risky pattern detected: '{}'", scene, pattern);
            }
        }

        // 检查是否有规范依据引用（处置建议必须引用规范）
        if (!output.contains("JTG") && output.length() > 100) {
            log.warn("[Guardrail][{}] Missing standard reference in output", scene);
        }
    }
}
