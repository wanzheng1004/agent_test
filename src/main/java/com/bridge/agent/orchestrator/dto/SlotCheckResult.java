package com.bridge.agent.orchestrator.dto;

/**
 * 槽位校验结果
 *
 * @param complete       是否所有必要槽位都已填充
 * @param guideQuestion  引导性追问文本（complete=false 时有值）
 * @param missingSlotKey 缺失的槽位名称
 */
public record SlotCheckResult(
        boolean complete,
        String guideQuestion,
        String missingSlotKey
) {
    public static SlotCheckResult ok() {
        return new SlotCheckResult(true, null, null);
    }

    public static SlotCheckResult missing(String slotKey, String question) {
        return new SlotCheckResult(false, question, slotKey);
    }
}
