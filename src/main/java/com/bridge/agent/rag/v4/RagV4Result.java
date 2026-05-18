package com.bridge.agent.rag.v4;

import com.bridge.agent.rag.ScoredDoc;

import java.util.List;

public record RagV4Result(
        String query,
        boolean found,
        List<ScoredDoc> rankedDocs,
        List<Citation> citations,
        List<RetrievalTrace> traces
) {
}
