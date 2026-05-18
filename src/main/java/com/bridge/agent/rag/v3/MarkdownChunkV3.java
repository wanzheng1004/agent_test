package com.bridge.agent.rag.v3;

import java.util.Map;

/**
 * V3 Markdown 切片。
 *
 * <p>职责：承载写入 SQLite 的最小检索单元，包括原文切片、分词文本和元数据。</p>
 */
public record MarkdownChunkV3(
        String id,
        String fileName,
        String filePath,
        String headingPath,
        String chunkText,
        String tokenizedText,
        Map<String, Object> metadata
) {}
