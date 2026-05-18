package com.bridge.agent.orchestrator.dto;

import com.bridge.agent.checkpoint.AgentCheckpoint;
import com.bridge.agent.core.AgentState;
import com.bridge.agent.core.TerminationReason;
import com.bridge.agent.runtime.AgentEvent;

import java.time.Instant;
import java.util.List;

public record AgentRunView(
        String runId,
        String sessionId,
        String agentName,
        String scene,
        AgentState state,
        TerminationReason terminationReason,
        String finalOutput,
        Instant startedAt,
        Instant endedAt,
        List<AgentEvent> events,
        List<AgentCheckpoint> checkpoints
) {
}
