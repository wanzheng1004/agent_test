package com.bridge.agent.rag.v3;

import com.huaban.analysis.jieba.JiebaSegmenter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * V3 Markdown 切片器。
 *
 * <p>设计意图：
 * 仿照 OpenClaw 的轻量 memory 方案，先把 Markdown 文档切成稳定 chunk，
 * 再把 chunk 写入 SQLite，后续统一走 BM25 + embedding 两路召回。</p>
 *
 * <p>面试要点：
 * “这里不再直接整篇 grep，而是先切片。这样 FTS 和向量检索的粒度一致，
 * 融合时不会出现‘一边按整篇、一边按局部’的结果错位问题。”</p>
 */
@Component
public class MarkdownChunkerV3 {

    private final JiebaSegmenter jieba = new JiebaSegmenter();

    @Value("${bridge.search-v3.chunk-size-chars:800}")
    private int chunkSizeChars;

    @Value("${bridge.search-v3.chunk-overlap-chars:120}")
    private int chunkOverlapChars;

    public List<MarkdownChunkV3> chunkDirectory(Path root) {
        if (root == null || !Files.exists(root)) {
            return List.of();
        }

        List<MarkdownChunkV3> chunks = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().toLowerCase().endsWith(".md"))
                    .sorted()
                    .forEach(path -> chunks.addAll(chunkFile(root, path)));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to chunk markdown directory: " + root, e);
        }
        return chunks;
    }

    private List<MarkdownChunkV3> chunkFile(Path root, Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read markdown file: " + file, e);
        }

        List<MarkdownChunkV3> chunks = new ArrayList<>();
        List<String> headingStack = new ArrayList<>();
        StringBuilder sectionBuffer = new StringBuilder();
        int sectionIndex = 0;

        for (String line : lines) {
            if (isHeading(line)) {
                if (sectionBuffer.length() > 0) {
                    chunks.addAll(flushSection(root, file, headingStack, sectionBuffer.toString(), sectionIndex++));
                    sectionBuffer.setLength(0);
                }
                updateHeadingStack(headingStack, line);
                continue;
            }
            sectionBuffer.append(line).append("\n");
        }

        if (sectionBuffer.length() > 0) {
            chunks.addAll(flushSection(root, file, headingStack, sectionBuffer.toString(), sectionIndex));
        }
        return chunks;
    }

    private List<MarkdownChunkV3> flushSection(Path root,
                                               Path file,
                                               List<String> headingStack,
                                               String rawBody,
                                               int sectionIndex) {
        String body = rawBody.trim();
        if (body.isBlank()) {
            return List.of();
        }

        String headingPath = String.join(" > ", headingStack);
        List<String> bodyChunks = splitBody(body);
        List<MarkdownChunkV3> chunks = new ArrayList<>();
        String relativePath = root.relativize(file).toString().replace("\\", "/");

        for (int i = 0; i < bodyChunks.size(); i++) {
            String chunkBody = bodyChunks.get(i);
            String chunkText = headingPath.isBlank()
                    ? chunkBody
                    : "【标题】" + headingPath + "\n" + chunkBody;

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("fileName", file.getFileName().toString());
            metadata.put("filePath", relativePath);
            metadata.put("headingPath", headingPath);
            metadata.put("sectionIndex", sectionIndex);
            metadata.put("chunkIndex", i);

            String stableIdSource = relativePath + "#" + headingPath + "#" + sectionIndex + "#" + i;
            String chunkId = UUID.nameUUIDFromBytes(stableIdSource.getBytes(StandardCharsets.UTF_8)).toString();

            chunks.add(new MarkdownChunkV3(
                    chunkId,
                    file.getFileName().toString(),
                    relativePath,
                    headingPath,
                    chunkText,
                    tokenizeForFts(headingPath + "\n" + chunkBody),
                    metadata
            ));
        }
        return chunks;
    }

    private List<String> splitBody(String body) {
        List<String> result = new ArrayList<>();
        String normalized = body.replace("\r", "").trim();
        if (normalized.length() <= chunkSizeChars) {
            result.add(normalized);
            return result;
        }

        int start = 0;
        while (start < normalized.length()) {
            int hardEnd = Math.min(normalized.length(), start + chunkSizeChars);
            int end = findChunkBoundary(normalized, start, hardEnd);
            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isBlank()) {
                result.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(start + 1, end - chunkOverlapChars);
        }
        return result;
    }

    private int findChunkBoundary(String text, int start, int hardEnd) {
        if (hardEnd >= text.length()) {
            return text.length();
        }

        int minAcceptable = start + Math.max(120, chunkSizeChars / 2);
        for (int i = hardEnd; i > minAcceptable; i--) {
            char ch = text.charAt(i - 1);
            if (ch == '\n' || ch == '。' || ch == '；' || ch == '！') {
                return i;
            }
        }
        return hardEnd;
    }

    /**
     * 为 FTS 额外生成一份带空格的中文分词文本。
     *
     * <p>设计意图：SQLite FTS5 对中文原文直接 MATCH 效果一般，
     * 因此额外保存一份 jieba 分词后的 tokenized_text，尽量贴近 OpenClaw 的“预处理后再索引”思路。</p>
     */
    public String tokenizeForFts(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : jieba.sentenceProcess(text == null ? "" : text)) {
            String normalized = token == null ? "" : token.trim();
            if (!normalized.isBlank() && normalized.length() >= 2) {
                tokens.add(normalized);
            }
        }
        return String.join(" ", tokens);
    }

    private boolean isHeading(String line) {
        return line != null && line.matches("^#{1,6}\\s+.+$");
    }

    private void updateHeadingStack(List<String> headingStack, String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        String heading = line.substring(level).trim();
        while (headingStack.size() >= level) {
            headingStack.remove(headingStack.size() - 1);
        }
        headingStack.add(heading);
    }
}
