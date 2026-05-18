package com.bridge.agent.tool;

import com.bridge.agent.rag.v4.Citation;
import com.bridge.agent.rag.v4.MarkdownRagV4Pipeline;
import com.bridge.agent.rag.v4.RagV4Result;
import com.bridge.agent.rag.v4.RetrievalTrace;
import com.bridge.agent.util.JsonUtil;
import org.springframework.stereotype.Component;

@Component
public class StandardRetrieveMarkdownToolV4 {

    private final MarkdownRagV4Pipeline pipeline;

    public StandardRetrieveMarkdownToolV4(MarkdownRagV4Pipeline pipeline) {
        this.pipeline = pipeline;
    }

    public String execute(String jsonInput) {
        String query = JsonUtil.getString(jsonInput, "defectQuery");
        RagV4Result result = pipeline.search(query == null ? "" : query);
        StringBuilder sb = new StringBuilder();
        sb.append("RAG v4 result with citations\n\n");
        for (Citation citation : result.citations()) {
            sb.append("[")
                    .append(citation.citationId())
                    .append("] ")
                    .append(citation.title())
                    .append(" ")
                    .append(citation.clause())
                    .append("\n")
                    .append(citation.excerpt())
                    .append("\n\n");
        }
        sb.append("Retrieval trace:\n");
        for (RetrievalTrace trace : result.traces()) {
            sb.append("- ")
                    .append(trace.stage())
                    .append(": hits=")
                    .append(trace.hitCount())
                    .append("\n");
        }
        return sb.toString();
    }
}
