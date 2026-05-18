package com.bridge.agent.orchestrator.dto;

import com.bridge.agent.core.TerminationReason;
import com.bridge.agent.runtime.AgentEvent;

import java.util.List;

public record AgentChatResult(
        String sessionId,
        String answer,
        String runId,
        SceneType scene,
        TerminationReason terminationReason,
        List<AgentEvent> events
) {
}
