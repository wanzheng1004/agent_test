package com.bridge.agent.advisor;

import com.bridge.agent.core.ToolSpec;
import com.bridge.agent.core.ToolSensitivity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class GuardrailAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(GuardrailAdvisor.class);
    private static final List<String> RISKY_OUTPUT_PATTERNS = List.of(
            "立即拆除",
            "禁止通行",
            "危桥",
            "必须立刻"
    );

    @Override
    public String getName() {
        return "GuardrailAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String input = request.prompt().getInstructions().stream()
                .map(message -> message.getText() == null ? "" : message.getText())
                .reduce("", (a, b) -> a + "\n" + b);
        GuardrailDecision decision = checkInput(input);
        decision.warnings().forEach(w -> log.warn("[Guardrail][input] {}", w));
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        try {
            String output = response.chatResponse().getResult().getOutput().getText();
            GuardrailDecision decision = checkOutput(output);
            decision.warnings().forEach(w -> log.warn("[Guardrail][output] {}", w));
        } catch (Exception e) {
            log.debug("Guardrail output check skipped: {}", e.getMessage());
        }
        return response;
    }

    public GuardrailDecision checkInput(String input) {
        if (input == null || input.isBlank()) {
            return GuardrailDecision.allow(GuardrailCheckType.INPUT);
        }
        List<String> warnings = new ArrayList<>();
        if (input.length() > 20_000) {
            warnings.add("Input is unusually long and may need truncation.");
        }
        return warnings.isEmpty()
                ? GuardrailDecision.allow(GuardrailCheckType.INPUT)
                : GuardrailDecision.warn(GuardrailCheckType.INPUT, warnings);
    }

    public GuardrailDecision checkOutput(String output) {
        if (output == null || output.isBlank()) {
            return GuardrailDecision.allow(GuardrailCheckType.OUTPUT);
        }
        List<String> warnings = new ArrayList<>();
        for (String pattern : RISKY_OUTPUT_PATTERNS) {
            if (output.contains(pattern)) {
                warnings.add("Risky absolute recommendation detected: " + pattern);
            }
        }
        if (output.length() > 100 && output.contains("处置") && !output.contains("JTG")) {
            warnings.add("Treatment recommendation does not cite a bridge inspection standard.");
        }
        return warnings.isEmpty()
                ? GuardrailDecision.allow(GuardrailCheckType.OUTPUT)
                : GuardrailDecision.warn(GuardrailCheckType.OUTPUT, warnings);
    }

    public GuardrailDecision checkTool(ToolSpec spec, String inputJson) {
        if (spec == null) {
            return GuardrailDecision.warn(GuardrailCheckType.TOOL, List.of("Unknown tool spec."));
        }
        if (spec.sensitivity() == ToolSensitivity.HIGH_RISK) {
            return new GuardrailDecision(
                    GuardrailCheckType.TOOL,
                    false,
                    true,
                    List.of("High-risk tool requires human review: " + spec.name()));
        }
        return GuardrailDecision.allow(GuardrailCheckType.TOOL);
    }
}
