package com.bridge.agent.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 安全护栏 Advisor —— 检测输出是否包含不当内容或超越规范范围的建议
 *
 * <p>After: 对 LLM 输出做规则校验，发现违规内容时进行标注。
 * 当前实现为规则校验（关键词黑名单），后续可升级为 LLM-as-judge。
 *
 * <p>面试要点：
 * "Guardrail 是独立的 Advisor，不影响主流程，只在 After 阶段检查输出。
 *  当前用关键词规则，未来可以换成 LLM-as-judge（用另一个 LLM 评估输出质量），
 *  因为 Advisor 接口屏蔽了实现细节，更换策略不影响 Agent 代码。"
 */
@Component
public class GuardrailAdvisor implements CallAroundAdvisor {

    private static final Logger log = LoggerFactory.getLogger(GuardrailAdvisor.class);

    /** 输出中不应出现的风险词（处置建议必须基于规范，不得出现无依据的绝对化表述） */
    private static final List<String> RISKY_PATTERNS = List.of(
            "立即拆除",      // 拆除决策需专业机构评估，Agent 不应单独建议
            "禁止通行",      // 封路决策有法规流程
            "危桥",          // 危桥认定需专业鉴定
            "必须立刻"       // 过于绝对化的紧急表述
    );

    /** 处置建议输出中必须包含的合规标记 */
    private static final String STANDARD_REF_HINT = "JTG";

    @Override
    public String getName() {
        return "GuardrailAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE; // 最后执行，检查最终输出
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest,
                                       CallAroundAdvisorChain chain) {
        AdvisedResponse response = chain.nextAroundCall(advisedRequest);

        String scene = (String) advisedRequest.adviseContext().getOrDefault("scene", "");

        // 只对 POST_INSPECTION 场景（处置建议）做严格校验
        if ("POST_INSPECTION".equals(scene)) {
            String output = response.response().getResult().getOutput().getText();
            validatePostInspectionOutput(output);
        }

        return response;
    }

    private void validatePostInspectionOutput(String output) {
        if (output == null) return;

        // 检查风险词
        for (String pattern : RISKY_PATTERNS) {
            if (output.contains(pattern)) {
                log.warn("Guardrail: risky pattern detected in output: '{}'", pattern);
                // 当前策略：仅记录警告，不拦截（可升级为替换或拒绝）
            }
        }

        // 检查是否有规范依据引用
        if (!output.contains(STANDARD_REF_HINT) && output.length() > 100) {
            log.warn("Guardrail: post-inspection output lacks standard reference (JTG...)");
        }
    }
}
