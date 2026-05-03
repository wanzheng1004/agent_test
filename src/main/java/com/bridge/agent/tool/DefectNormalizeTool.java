package com.bridge.agent.tool;

import com.bridge.agent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 工具：病害描述规范化
 *
 * <p>工具名：normalize_defect
 * <p>输入：{"rawDescription": "...", "supplementInfo": "...", "bridgeId": "...", "component": "..."}
 * <p>输出：标准化病害描述 JSON（含等级、规范引用、定级依据）
 *
 * <p>将检测员的自由文本描述转化为符合 JTG/T H21 规范的结构化记录。
 * 这是解决"同一病害不同人描述不一致"痛点的核心工具。
 */
@Service
public class DefectNormalizeTool {

    private static final Logger log = LoggerFactory.getLogger(DefectNormalizeTool.class);

    private final ChatClient chatClient;
    private final String normalizePrompt;

    public DefectNormalizeTool(ChatClient.Builder builder) {
        this.chatClient = builder.build();
        this.normalizePrompt = loadPrompt();
    }

    public String execute(String jsonInput) {
        String rawDescription  = JsonUtil.getString(jsonInput, "rawDescription");
        String supplementInfo  = JsonUtil.getString(jsonInput, "supplementInfo");
        String bridgeId        = JsonUtil.getString(jsonInput, "bridgeId");
        String component       = JsonUtil.getString(jsonInput, "component");

        if (rawDescription == null || rawDescription.isBlank()) {
            return "错误：rawDescription 不能为空";
        }

        String prompt = normalizePrompt
                .replace("{rawDescription}", rawDescription)
                .replace("{supplementInfo}", supplementInfo != null ? supplementInfo : "无")
                .replace("{bridgeId}",       bridgeId != null ? bridgeId : "未知")
                .replace("{component}",      component != null ? component : "未指定");

        String result = chatClient.prompt()
                .system(prompt)
                .user("请对以上病害信息进行规范化处理，直接输出 JSON，不要其他文字。")
                .call()
                .content();

        log.debug("Defect normalized: input={}, output={}", rawDescription.substring(0, Math.min(50, rawDescription.length())), result);
        return result;
    }

    private String loadPrompt() {
        try {
            return new ClassPathResource("prompts/defect-normalize.st")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load defect-normalize.st, using default");
            return "将以下病害描述规范化为JSON：rawDescription={rawDescription}，supplementInfo={supplementInfo}，bridgeId={bridgeId}，component={component}";
        }
    }
}
