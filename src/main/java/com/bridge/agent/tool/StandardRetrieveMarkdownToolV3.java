package com.bridge.agent.tool;

import com.bridge.agent.rag.ScoredDoc;
import com.bridge.agent.rag.v3.MarkdownHybridSearchV3;
import com.bridge.agent.rag.v3.SearchResultV3;
import com.bridge.agent.rag.v3.SearchTraceV3;
import com.bridge.agent.util.JsonUtil;
import org.springframework.stereotype.Service;

/**
 * V3 工具：OpenClaw 风格 Markdown Hybrid Search。
 *
 * <p>设计意图：
 * 这一版不再直接整篇 grep，而是参考 OpenClaw：
 * Markdown -> chunk -> SQLite -> FTS5/BM25 + vector search -> fusion。</p>
 */
@Service
public class StandardRetrieveMarkdownToolV3 {

    private final MarkdownHybridSearchV3 hybridSearch;

    public StandardRetrieveMarkdownToolV3(MarkdownHybridSearchV3 hybridSearch) {
        this.hybridSearch = hybridSearch;
    }

    public String execute(String jsonInput) {
        String query = JsonUtil.getString(jsonInput, "defectQuery");
        if (query == null || query.isBlank()) {
            return "错误：defectQuery 不能为空";
        }

        SearchResultV3 result = hybridSearch.search(query);
        if (!result.found()) {
            return "未在 Markdown V3 索引库中检索到与 [" + query + "] 相关的条款，"
                    + "请补充病害类型、构件、位置或更具体的规范术语。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Markdown V3 hybrid search 命中了以下结果：\n\n");
        for (int i = 0; i < result.rerankedHits().size(); i++) {
            ScoredDoc doc = result.rerankedHits().get(i);
            sb.append(doc.toContextText(i)).append("\n\n");
        }

        sb.append("索引状态：");
        sb.append(result.indexRebuilt() ? "本次已重建索引" : "复用已有索引");
        sb.append("，当前 chunk 数量 = ").append(result.indexedChunkCount()).append("\n");

        sb.append("检索轨迹：\n");
        for (SearchTraceV3 trace : result.traces()) {
            sb.append(String.format("- [%s] %s -> %d hits%n",
                    trace.stage(), trace.query(), trace.hitCount()));
        }
        return sb.toString();
    }
}
