package com.bridge.agent.rag.v4;

import com.bridge.agent.rag.ScoredDoc;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RagEvaluatorTest {

    @Test
    void computesRecallMrrAndCitationCoverage() {
        GoldenQuestion question = new GoldenQuestion(
                "q1",
                "How to rate a crack?",
                Set.of("doc-2"),
                Set.of("6.3.2"));
        RagV4Result result = new RagV4Result(
                question.question(),
                true,
                List.of(
                        new ScoredDoc("doc-1", "other", "RERANK", 0.9, Map.of()),
                        new ScoredDoc("doc-2", "target", "RERANK", 0.8, Map.of())),
                List.of(new Citation("C1", "doc-2", "JTG", "6.3.2", "target", 0.8, Map.of())),
                List.of());

        RagEvaluationResult eval = new RagEvaluator()
                .evaluate(List.of(question), List.of(result), 2);

        assertThat(eval.questionCount()).isEqualTo(1);
        assertThat(eval.recallAtK()).isEqualTo(1.0);
        assertThat(eval.meanReciprocalRank()).isEqualTo(0.5);
        assertThat(eval.citationCoverage()).isEqualTo(1.0);
    }
}
