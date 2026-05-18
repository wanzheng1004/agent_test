package com.bridge.agent.rag.v4;

public record RetrievalTrace(
        String stage,
        String query,
        int hitCount,
        long elapsedMs
) {
}
