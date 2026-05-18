package com.bridge.agent.orchestrator.dto;

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
