package com.bridge.agent.orchestrator.dto;

import com.bridge.agent.checkpoint.AgentCheckpoint;
import com.bridge.agent.core.TerminationReason;
import com.bridge.agent.runtime.AgentEvent;

import java.util.List;

public record AgentRunResponse(
        String sessionId,
        String runId,
        SceneType scene,
        TerminationReason terminationReason,
        String answer,
        List<AgentEvent> events,
        List<AgentCheckpoint> checkpoints
) {
}
