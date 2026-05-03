package com.bridge.agent.rag;

import java.util.List;

/**
 * RAG 管道四阶段执行结果
 *
 * <p>保留每阶段的中间结果，用于：
 * <ul>
 *   <li>LoggingAdvisor 输出详细的检索过程日志</li>
 *   <li>调试时观察各阶段的召回质量</li>
 *   <li>A/B 测试不同检索策略的效果</li>
 * </ul>
 */
public class RagResult {

    private final String originalQuery;
    private String rewrittenQuery;          // Stage 1: 查询改写后（可能与原查询相同）

    private List<ScoredDoc> sparseResults;  // Stage 2a: BM25 稀疏检索结果
    private List<ScoredDoc> denseResults;   // Stage 2b: Qdrant 向量稠密检索结果
    private List<ScoredDoc> fusedResults;   // Stage 3: RRF 融合结果
    private List<ScoredDoc> finalResults;   // Stage 4: Reranker 精排最终结果

    // 各阶段耗时（毫秒）
    private long sparseLatencyMs;
    private long denseLatencyMs;
    private long fusionLatencyMs;
    private long rerankLatencyMs;

    public RagResult(String originalQuery) {
        this.originalQuery = originalQuery;
        this.rewrittenQuery = originalQuery; // 默认不改写
    }

    /** 将最终结果格式化为 LLM 上下文文本（注入 Prompt） */
    public String toContextText() {
        if (finalResults == null || finalResults.isEmpty()) {
            return "未检索到相关规范文档。";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < finalResults.size(); i++) {
            sb.append(finalResults.get(i).toContextText(i)).append("\n\n");
        }
        return sb.toString();
    }

    /** 调试信息摘要 */
    public String toDebugSummary() {
        return String.format(
                "RAG[query=%s] sparse=%d, dense=%d, fused=%d, final=%d | " +
                "latency: sparse=%dms dense=%dms fusion=%dms rerank=%dms",
                originalQuery,
                sparseResults != null ? sparseResults.size() : 0,
                denseResults  != null ? denseResults.size()  : 0,
                fusedResults  != null ? fusedResults.size()  : 0,
                finalResults  != null ? finalResults.size()  : 0,
                sparseLatencyMs, denseLatencyMs, fusionLatencyMs, rerankLatencyMs);
    }

    // Getters / Setters
    public String getOriginalQuery()              { return originalQuery; }
    public String getRewrittenQuery()             { return rewrittenQuery; }
    public List<ScoredDoc> getSparseResults()     { return sparseResults; }
    public List<ScoredDoc> getDenseResults()      { return denseResults; }
    public List<ScoredDoc> getFusedResults()      { return fusedResults; }
    public List<ScoredDoc> getFinalResults()      { return finalResults; }

    public void setRewrittenQuery(String q)       { this.rewrittenQuery = q; }
    public void setSparseResults(List<ScoredDoc> r) { this.sparseResults = r; }
    public void setDenseResults(List<ScoredDoc> r)  { this.denseResults = r; }
    public void setFusedResults(List<ScoredDoc> r)  { this.fusedResults = r; }
    public void setFinalResults(List<ScoredDoc> r)  { this.finalResults = r; }
    public void setSparseLatencyMs(long ms)       { this.sparseLatencyMs = ms; }
    public void setDenseLatencyMs(long ms)        { this.denseLatencyMs = ms; }
    public void setFusionLatencyMs(long ms)       { this.fusionLatencyMs = ms; }
    public void setRerankLatencyMs(long ms)       { this.rerankLatencyMs = ms; }
}
