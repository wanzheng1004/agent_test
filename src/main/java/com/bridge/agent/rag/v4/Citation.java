package com.bridge.agent.rag.v4;

import java.util.Map;

public record Citation(
        String citationId,
        String sourceId,
        String title,
        String clause,
        String excerpt,
        double score,
        Map<String, Object> metadata
) {
}
