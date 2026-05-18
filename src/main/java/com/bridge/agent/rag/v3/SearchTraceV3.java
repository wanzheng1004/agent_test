package com.bridge.agent.rag.v3;

/**
 * V3 搜索轨迹。
 *
 * <p>职责：记录每个阶段用了什么 query、命中了多少结果，方便调试 hybrid search。</p>
 */
public record SearchTraceV3(
        String stage,
        String query,
        int hitCount
) {}
