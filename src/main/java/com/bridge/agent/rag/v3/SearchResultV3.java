package com.bridge.agent.rag.v3;

import com.bridge.agent.rag.ScoredDoc;

import java.util.List;

/**
 * V3 搜索结果。
 *
 * @param originalQuery 用户原始查询
 * @param indexRebuilt 本次查询前是否触发了索引重建
 * @param indexedChunkCount 当前 SQLite 中的切片数量
 * @param traces 检索轨迹
 * @param sparseHits FTS5 + BM25 结果
 * @param denseHits embedding + vector 结果
 * @param fusedHits RRF 融合结果
 * @param rerankedHits rerank 精排结果
 */
public record SearchResultV3(
        String originalQuery,
        boolean indexRebuilt,
        int indexedChunkCount,
        List<SearchTraceV3> traces,
        List<ScoredDoc> sparseHits,
        List<ScoredDoc> denseHits,
        List<ScoredDoc> fusedHits,
        List<ScoredDoc> rerankedHits
) {

    public boolean found() {
        return rerankedHits != null && !rerankedHits.isEmpty();
    }
}
