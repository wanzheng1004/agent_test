package com.bridge.agent.rag.v2;

/**
 * V2 搜索尝试记录。
 *
 * <p>职责：记录每一轮检索到底用了什么 query、属于哪个阶段、命中了多少结果，
 * 便于排查“为什么第一次没搜到，第二次搜到了”。</p>
 */
public record SearchAttemptV2(
        String stage,
        String query,
        int hitCount
) {}
