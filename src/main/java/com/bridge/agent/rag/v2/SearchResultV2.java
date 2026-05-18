package com.bridge.agent.rag.v2;

import com.bridge.agent.rag.ScoredDoc;

import java.util.List;

/**
 * V2 搜索结果。
 *
 * @param originalQuery 用户原始查询
 * @param finalQuery 最终命中的检索表达
 * @param attempts 各阶段检索尝试轨迹
 * @param hits 最终命中的结果
 */
public record SearchResultV2(
        String originalQuery,
        String finalQuery,
        List<SearchAttemptV2> attempts,
        List<ScoredDoc> hits
) {

    public boolean found() {
        return hits != null && !hits.isEmpty();
    }
}
