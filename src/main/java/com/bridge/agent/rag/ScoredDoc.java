package com.bridge.agent.rag;

/**
 * 带分数的检索文档
 *
 * @param id      文档唯一标识
 * @param content 文档内容文本
 * @param source  来源：SPARSE（BM25）/ DENSE（向量）/ FUSED（RRF融合后）
 * @param score   相关性分数（RRF 融合后为 RRF score，Rerank 后为 rerank score）
 * @param metadata 文档元数据（条款编号、章节等）
 */
public record ScoredDoc(
        String id,
        String content,
        String source,
        double score,
        java.util.Map<String, Object> metadata
) {
    /** 更新分数（用于 RRF 融合和 Rerank 阶段） */
    public ScoredDoc withScore(double newScore) {
        return new ScoredDoc(id, content, source, newScore, metadata);
    }

    /** 更新来源标记（用于 RRF 融合后标记为 FUSED） */
    public ScoredDoc withSource(String newSource) {
        return new ScoredDoc(id, content, newSource, score, metadata);
    }

    /** 格式化为 LLM 可读的上下文文本 */
    public String toContextText(int index) {
        String clauseRef = metadata != null && metadata.containsKey("clause")
                ? "[" + metadata.get("clause") + "] " : "";
        return String.format("[文档%d] %s%s", index + 1, clauseRef, content);
    }
}
