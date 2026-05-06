package com.bridge.agent.tool;

import com.bridge.agent.rag.FourStageRagPipeline;
import com.bridge.agent.rag.RagResult;
import com.bridge.agent.util.JsonUtil;
import org.springframework.stereotype.Service;

/**
 * 工具：查询桥梁检测规范条款（双路 RAG 管道）
 *
 * <p>内部采用四阶段 RAG：
 * MySQL FULLTEXT + Qdrant 向量检索 + RRF 融合 + BGE Reranker 精排。
 */
@Service
public class StandardRetrieveTool {

    private final FourStageRagPipeline ragPipeline;

    public StandardRetrieveTool(FourStageRagPipeline ragPipeline) {
        this.ragPipeline = ragPipeline;
    }

    public String execute(String jsonInput) {
        String query = JsonUtil.getString(jsonInput, "defectQuery");
        if (query == null || query.isBlank()) {
            return "错误：defectQuery 不能为空";
        }

        RagResult result = ragPipeline.retrieve(query);
        if (result.getFinalResults() == null || result.getFinalResults().isEmpty()) {
            return "未检索到与 [" + query + "] 相关的规范条款，建议直接人工复核 JTG/T H21-2011 对应章节。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("检索到以下相关规范条款：\n\n");
        sb.append(result.toContextText());
        sb.append("\n（检索来源：BM25 + 向量双路召回，经 RRF 融合 + BGE Reranker 重排）");
        return sb.toString();
    }
}
