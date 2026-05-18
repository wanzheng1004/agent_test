package com.bridge.agent.rag.v4;

public record RagEvaluationResult(
        int questionCount,
        double recallAtK,
        double meanReciprocalRank,
        double citationCoverage
) {
}
