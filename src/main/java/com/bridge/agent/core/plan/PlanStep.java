package com.bridge.agent.core.plan;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Plan & Execute 中的单个执行步骤
 *
 * @param tool        工具名称（对应 ToolRegistry 中的 key）
 * @param input       工具入参（原始 JSON 字符串或 Map）
 * @param description 步骤说明（人类可读，用于日志和报告）
 * @param outputKey   本步骤结果的暂存 key，后续步骤可通过 ${outputKey} 引用
 */
public record PlanStep(
        @JsonProperty("tool") String tool,
        @JsonProperty("input") Object input,
        @JsonProperty("description") String description,
        @JsonProperty("outputKey") String outputKey
) {}
