package com.bridge.agent.rag.v4;

import java.util.Set;

public record GoldenQuestion(
        String id,
        String question,
        Set<String> expectedSourceIds,
        Set<String> expectedClauses
) {
}
