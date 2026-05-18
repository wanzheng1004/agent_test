package com.bridge.agent.runtime;

import java.time.Instant;
import java.util.Map;

public record AgentEvent(
        String eventId,
        String runId,
        AgentEventType type,
        String agentName,
        String message,
        Map<String, Object> attributes,
        Instant occurredAt,
        long elapsedMs
) {
}
