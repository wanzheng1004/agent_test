package com.bridge.agent.rag.v2;

import java.util.List;

/**
 * Markdown grep 命中结果。
 *
 * <p>职责：承载单次文件扫描命中的片段，包括文件名、标题、行号和上下文。</p>
 *
 * @param fileName 命中文件名
 * @param heading 命中的最近标题
 * @param lineNumber 命中行号
 * @param score 规则分数
 * @param matchedLine 直接命中的那一行
 * @param contextLines 命中附近上下文
 */
public record FileSearchHitV2(
        String fileName,
        String heading,
        int lineNumber,
        double score,
        String matchedLine,
        List<String> contextLines
) {

    public String toContextText(int index) {
        StringBuilder sb = new StringBuilder();
        if (index >= 0) {
            sb.append(String.format("[文档%d] %s", index + 1, fileName));
        } else {
            sb.append(fileName);
        }
        if (heading != null && !heading.isBlank()) {
            sb.append(" / ").append(heading);
        }
        sb.append(" / line ").append(lineNumber).append("\n");
        for (String line : contextLines) {
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }
}
