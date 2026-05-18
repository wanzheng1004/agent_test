package com.bridge.agent.rag.v4;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class RagEvaluator {

    public RagEvaluationResult evaluate(List<GoldenQuestion> questions,
                                        List<RagV4Result> results,
                                        int k) {
        if (questions == null || questions.isEmpty()) {
            return new RagEvaluationResult(0, 0.0, 0.0, 0.0);
        }
        double recallSum = 0.0;
        double mrrSum = 0.0;
        double citationCoverageSum = 0.0;

        for (int i = 0; i < questions.size(); i++) {
            GoldenQuestion question = questions.get(i);
            RagV4Result result = i < results.size() ? results.get(i) : emptyResult(question.question());
            recallSum += recallAtK(question.expectedSourceIds(), result, k);
            mrrSum += reciprocalRank(question.expectedSourceIds(), result);
            citationCoverageSum += citationCoverage(question.expectedClauses(), result);
        }

        int count = questions.size();
        return new RagEvaluationResult(
                count,
                recallSum / count,
                mrrSum / count,
                citationCoverageSum / count);
    }

    private double recallAtK(Set<String> expectedSourceIds, RagV4Result result, int k) {
        if (expectedSourceIds == null || expectedSourceIds.isEmpty()) {
            return 1.0;
        }
        int limit = Math.min(Math.max(k, 1), result.rankedDocs().size());
        long hits = result.rankedDocs().subList(0, limit).stream()
                .filter(doc -> expectedSourceIds.contains(doc.id()))
                .count();
        return (double) hits / expectedSourceIds.size();
    }

    private double reciprocalRank(Set<String> expectedSourceIds, RagV4Result result) {
        if (expectedSourceIds == null || expectedSourceIds.isEmpty()) {
            return 1.0;
        }
        for (int i = 0; i < result.rankedDocs().size(); i++) {
            if (expectedSourceIds.contains(result.rankedDocs().get(i).id())) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private double citationCoverage(Set<String> expectedClauses, RagV4Result result) {
        if (expectedClauses == null || expectedClauses.isEmpty()) {
            return result.citations().isEmpty() ? 0.0 : 1.0;
        }
        long hits = result.citations().stream()
                .map(Citation::clause)
                .filter(expectedClauses::contains)
                .count();
        return (double) hits / expectedClauses.size();
    }

    private RagV4Result emptyResult(String query) {
        return new RagV4Result(query, false, List.of(), List.of(), List.of());
    }
}
