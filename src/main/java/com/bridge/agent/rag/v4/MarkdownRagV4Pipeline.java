package com.bridge.agent.rag.v4;

import com.bridge.agent.rag.ScoredDoc;
import com.bridge.agent.rag.v3.MarkdownHybridSearchV3;
import com.bridge.agent.rag.v3.SearchResultV3;
import com.bridge.agent.rag.v3.SearchTraceV3;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class MarkdownRagV4Pipeline {

    private final MarkdownHybridSearchV3 v3;

    public MarkdownRagV4Pipeline(MarkdownHybridSearchV3 v3) {
        this.v3 = v3;
    }

    public RagV4Result search(String query) {
        long start = System.currentTimeMillis();
        SearchResultV3 result = v3.search(query);
        List<ScoredDoc> rankedDocs = result.rerankedHits() == null ? List.of() : result.rerankedHits();
        List<Citation> citations = toCitations(rankedDocs);
        List<RetrievalTrace> traces = toTraces(result.traces(), System.currentTimeMillis() - start);
        return new RagV4Result(result.originalQuery(), result.found(), rankedDocs, citations, traces);
    }

    private List<Citation> toCitations(List<ScoredDoc> docs) {
        AtomicInteger index = new AtomicInteger(1);
        return docs.stream()
                .map(doc -> {
                    Map<String, Object> metadata = doc.metadata() == null ? Map.of() : doc.metadata();
                    String citationId = "C" + index.getAndIncrement();
                    String clause = Objects.toString(metadata.getOrDefault("clause", metadata.getOrDefault("heading", "")), "");
                    String title = Objects.toString(metadata.getOrDefault("title", metadata.getOrDefault("file", doc.id())), doc.id());
                    return new Citation(
                            citationId,
                            doc.id(),
                            title,
                            clause,
                            excerpt(doc.content()),
                            doc.score(),
                            metadata);
                })
                .toList();
    }

    private List<RetrievalTrace> toTraces(List<SearchTraceV3> traces, long totalElapsedMs) {
        if (traces == null || traces.isEmpty()) {
            return List.of(new RetrievalTrace("V4_WRAP", "", 0, totalElapsedMs));
        }
        return traces.stream()
                .map(trace -> new RetrievalTrace(trace.stage(), trace.query(), trace.hitCount(), 0))
                .toList();
    }

    private String excerpt(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 220 ? content : content.substring(0, 220);
    }
}
