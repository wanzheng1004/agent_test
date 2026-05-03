package com.bridge.agent.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 意图分类结果 —— 单次 LLM 调用的结构化输出
 *
 * <p>由 IntentClassifier 通过 Spring AI 结构化输出解析得到。
 * 包含场景类型和关键槽位信息。
 */
public record IntentResult(
        @JsonProperty("scene")            SceneType scene,
        @JsonProperty("bridgeId")         String bridgeId,        // 可能为 null
        @JsonProperty("defectDescription") String defectDescription, // 可能为 null
        @JsonProperty("standardRef")      String standardRef,     // 可能为 null
        @JsonProperty("missingSlots")     List<String> missingSlots // 缺失的必要槽位
) {
    /** 是否所有必要槽位都已填充 */
    public boolean isComplete() {
        return missingSlots == null || missingSlots.isEmpty();
    }
}
