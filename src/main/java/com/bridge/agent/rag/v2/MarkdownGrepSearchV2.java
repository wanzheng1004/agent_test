package com.bridge.agent.rag.v2;

import com.bridge.agent.llm.OpenAiCompatibleChatService;
import com.bridge.agent.rag.ScoredDoc;
import com.huaban.analysis.jieba.JiebaSegmenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * V2 Markdown + grep 风格搜索。
 *
 * <p>设计意图：
 * 不依赖向量库，不依赖数据库，把规范资料整理成 Markdown 文件后，
 * 直接做“全文扫描 + 标题上下文截取”的轻量检索。</p>
 *
 * <p>检索流程：
 * 1. 原始 query 直接检索
 * 2. 术语归一化后再次检索
 * 3. 规则同义词扩展后再次检索
 * 4. 最后让 LLM 做受控 query rewrite，再检索 2~3 次</p>
 *
 * <p>面试要点：
 * “当规范文档规模不大时，未必需要重型 RAG。
 * Markdown 条文化后，用 grep 风格全文检索往往更轻、更可解释，也更容易调试。”</p>
 */
@Service
public class MarkdownGrepSearchV2 {

    private static final Logger log = LoggerFactory.getLogger(MarkdownGrepSearchV2.class);

    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+.+$");

    /**
     * 轻量领域术语映射。
     *
     * <p>设计意图：先做显式归一化和有限扩展，只有这些都失败时才交给 LLM 改写，
     * 避免“一次没搜到就让大模型自由发挥”。</p>
     */
    private static final List<SynonymGroup> SYNONYMS = List.of(
            new SynonymGroup("裂缝", List.of("裂纹", "开裂", "缝")),
            new SynonymGroup("渗水", List.of("渗漏", "漏水", "潮湿")),
            new SynonymGroup("剥落", List.of("掉皮", "脱落")),
            new SynonymGroup("露筋", List.of("钢筋外露")),
            new SynonymGroup("墩柱", List.of("桥墩", "墩子")),
            new SynonymGroup("坑槽", List.of("坑洞", "坑漕"))
    );

    private final OpenAiCompatibleChatService chatService;
    private final JiebaSegmenter jieba = new JiebaSegmenter();

    @Value("${bridge.search-v2.markdown-root:src/main/resources/standards-md}")
    private String markdownRoot;

    @Value("${bridge.search-v2.top-k:5}")
    private int topK;

    @Value("${bridge.search-v2.context-radius:2}")
    private int contextRadius;

    @Value("${bridge.search-v2.min-hit-threshold:2}")
    private int minHitThreshold;

    public MarkdownGrepSearchV2(OpenAiCompatibleChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 对外搜索入口。
     */
    public SearchResultV2 search(String originalQuery) {
        String safeQuery = originalQuery == null ? "" : originalQuery.trim();
        List<SearchAttemptV2> attempts = new ArrayList<>();

        SearchResultV2 direct = searchSingle("DIRECT", safeQuery, attempts);
        if (goodEnough(direct.hits())) {
            return direct;
        }

        String normalized = normalizeTerminology(safeQuery);
        if (!normalized.equals(safeQuery)) {
            SearchResultV2 normalizedResult = searchSingle("NORMALIZED", normalized, attempts);
            if (goodEnough(normalizedResult.hits())) {
                return normalizedResult;
            }
        }

        List<String> expandedQueries = expandQueries(normalized);
        SearchResultV2 expandedResult = searchMany("EXPANDED", safeQuery, expandedQueries, attempts);
        if (goodEnough(expandedResult.hits())) {
            return expandedResult;
        }

        List<String> rewrittenQueries = rewriteQueriesByLlm(normalized);
        SearchResultV2 rewrittenResult = searchMany("LLM_REWRITE", safeQuery, rewrittenQueries, attempts);
        if (rewrittenResult.found()) {
            return rewrittenResult;
        }

        return new SearchResultV2(safeQuery, normalized, attempts, List.of());
    }

    private SearchResultV2 searchSingle(String stage, String query, List<SearchAttemptV2> attempts) {
        List<FileSearchHitV2> hits = grepMarkdown(query);
        attempts.add(new SearchAttemptV2(stage, query, hits.size()));
        return new SearchResultV2(query, query, attempts, hits.stream()
                .map(this::toScoredDoc)
                .toList());
    }

    private SearchResultV2 searchMany(String stage,
                                      String originalQuery,
                                      List<String> queries,
                                      List<SearchAttemptV2> attempts) {
        List<FileSearchHitV2> merged = new ArrayList<>();
        String finalQuery = originalQuery;

        for (String query : queries) {
            if (query == null || query.isBlank()) {
                continue;
            }
            List<FileSearchHitV2> hits = grepMarkdown(query);
            attempts.add(new SearchAttemptV2(stage, query, hits.size()));
            if (!hits.isEmpty()) {
                finalQuery = query;
                merged.addAll(hits);
            }
        }

        List<FileSearchHitV2> deduped = deduplicateAndSort(merged);
        return new SearchResultV2(
                originalQuery,
                finalQuery,
                attempts,
                deduped.stream().map(this::toScoredDoc).toList()
        );
    }

    /**
     * grep 风格全文扫描。
     *
     * <p>做法很直接：
     * 1. 遍历 markdown 文件
     * 2. 按行扫描
     * 3. token 命中时收集“最近标题 + 命中行 + 周围几行”</p>
     */
    private List<FileSearchHitV2> grepMarkdown(String query) {
        Path root = Paths.get(markdownRoot);
        if (!Files.exists(root)) {
            log.warn("Markdown search root does not exist: {}", root.toAbsolutePath());
            return List.of();
        }

        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return List.of();
        }

        try (Stream<Path> files = Files.walk(root)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .flatMap(path -> grepFile(path, tokens).stream())
                    .sorted(Comparator.comparingDouble(FileSearchHitV2::score).reversed())
                    .limit(topK)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Markdown grep failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private List<FileSearchHitV2> grepFile(Path file, List<String> tokens) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<FileSearchHitV2> hits = new ArrayList<>();
            String currentHeading = "";

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (HEADING_PATTERN.matcher(line).matches()) {
                    currentHeading = line.replaceFirst("^#+\\s*", "").trim();
                }

                double score = matchScore(line, currentHeading, tokens);
                if (score <= 0) {
                    continue;
                }

                int start = Math.max(0, i - contextRadius);
                int end = Math.min(lines.size() - 1, i + contextRadius);
                List<String> context = new ArrayList<>();
                for (int j = start; j <= end; j++) {
                    context.add(lines.get(j));
                }

                hits.add(new FileSearchHitV2(
                        file.getFileName().toString(),
                        currentHeading,
                        i + 1,
                        score,
                        line,
                        context
                ));
            }
            return hits;
        } catch (Exception e) {
            log.warn("Failed to grep file {}: {}", file, e.getMessage());
            return List.of();
        }
    }

    /**
     * 轻量规则打分。
     *
     * <p>同一行命中的 token 越多，分数越高；
     * 标题也命中时，再额外加权。</p>
     */
    private double matchScore(String line, String heading, List<String> tokens) {
        double score = 0.0;
        String normalizedLine = normalizeText(line);
        String normalizedHeading = normalizeText(heading);

        for (String token : tokens) {
            String normalizedToken = normalizeText(token);
            if (normalizedToken.isBlank()) {
                continue;
            }
            if (normalizedLine.contains(normalizedToken)) {
                score += 2.0;
            }
            if (!normalizedHeading.isBlank() && normalizedHeading.contains(normalizedToken)) {
                score += 1.0;
            }
        }

        if (!line.isBlank() && line.equals(heading)) {
            score -= 0.5;
        }
        return score;
    }

    private List<String> tokenize(String query) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isBlank()) {
            return List.of();
        }

        Set<String> dedup = new LinkedHashSet<>();
        dedup.add(normalizedQuery);
        for (String token : jieba.sentenceProcess(normalizedQuery)) {
            String normalized = token == null ? "" : token.trim();
            if (!normalized.isBlank() && normalized.length() >= 2) {
                dedup.add(normalized);
            }
        }
        return new ArrayList<>(dedup);
    }

    private String normalizeTerminology(String query) {
        String result = query;
        for (SynonymGroup group : SYNONYMS) {
            for (String alias : group.aliases()) {
                result = result.replace(alias, group.canonical());
            }
        }
        return result;
    }

    private List<String> expandQueries(String query) {
        Set<String> queries = new LinkedHashSet<>();
        queries.add(query);
        for (SynonymGroup group : SYNONYMS) {
            if (query.contains(group.canonical())) {
                for (String alias : group.aliases()) {
                    queries.add(query.replace(group.canonical(), alias));
                }
            }
        }
        return new ArrayList<>(queries);
    }

    /**
     * 只让 LLM 做受控检索改写，不让它直接回答业务问题。
     */
    private List<String> rewriteQueriesByLlm(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        String prompt = """
                你是桥梁规范检索改写助手。
                请把用户问题改写成最多 3 条适合 markdown 全文检索的短查询。
                要求：
                1. 只输出查询，不要解释
                2. 每行一条
                3. 尽量使用规范术语
                4. 不要编造尺寸、等级、桥梁编号
                5. 每条不超过 20 个字
                """;
        try {
            String response = chatService.complete(prompt, query);
            return response.lines()
                    .map(String::trim)
                    .map(line -> line.replaceFirst("^[-*\\d.\\s]+", ""))
                    .filter(line -> !line.isBlank())
                    .distinct()
                    .limit(3)
                    .toList();
        } catch (Exception e) {
            log.warn("Markdown grep LLM rewrite failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<FileSearchHitV2> deduplicateAndSort(List<FileSearchHitV2> hits) {
        Map<String, FileSearchHitV2> dedup = new LinkedHashMap<>();
        for (FileSearchHitV2 hit : hits) {
            String key = hit.fileName() + "#" + hit.lineNumber();
            FileSearchHitV2 existing = dedup.get(key);
            if (existing == null || hit.score() > existing.score()) {
                dedup.put(key, hit);
            }
        }
        return dedup.values().stream()
                .sorted(Comparator.comparingDouble(FileSearchHitV2::score).reversed())
                .limit(topK)
                .toList();
    }

    private boolean goodEnough(List<?> hits) {
        return hits != null && hits.size() >= minHitThreshold;
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    }

    private ScoredDoc toScoredDoc(FileSearchHitV2 hit) {
        return new ScoredDoc(
                hit.fileName() + ":" + hit.lineNumber(),
                hit.toContextText(-1),
                "MARKDOWN_GREP_V2",
                hit.score(),
                Map.of(
                        "fileName", hit.fileName(),
                        "heading", hit.heading() == null ? "" : hit.heading(),
                        "lineNumber", hit.lineNumber(),
                        "matchedLine", hit.matchedLine()
                )
        );
    }

    private record SynonymGroup(String canonical, List<String> aliases) {}
}
