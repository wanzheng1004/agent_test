package com.bridge.agent.checkpoint;

import com.bridge.agent.core.AgentState;
import com.bridge.agent.core.TerminationReason;

import java.time.Instant;
import java.util.Map;

public record AgentCheckpoint(
        String checkpointId,
        String runId,
        String stage,
        int sequence,
        AgentState state,
        TerminationReason terminationReason,
        Map<String, Object> payload,
        Instant createdAt
) {
}
