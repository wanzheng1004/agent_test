package com.bridge.agent.rag.v3;

import com.bridge.agent.rag.BgeRerankerClient;
import com.bridge.agent.rag.ScoredDoc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V3 Markdown Hybrid Search。
 *
 * <p>设计意图：
 * 仿照 OpenClaw 的 built-in memory 流程，把 markdown 先 chunk 后落 SQLite，
 * 搜索时显式走两路：
 * 1. FTS5 + BM25
 * 2. embedding + vector search
 * 先做 RRF 融合，再做 rerank 精排。</p>
 *
 * <p>面试要点：
 * “只做 hybrid retrieve 还不够，RRF 只能解决‘两路召回怎么合并’，
 * 不能解决‘哪条候选最贴近当前 query’。
 * 所以 V3 在融合后补了一层 BGE reranker，把 topN 候选再做一次相关性精排。”</p>
 */
@Service
public class MarkdownHybridSearchV3 {

    private final MarkdownChunkerV3 chunker;
    private final SqliteHybridIndexV3 sqliteIndex;
    private final BgeRerankerClient rerankerClient;

    @Value("${bridge.search-v3.markdown-root:src/main/resources/standards-md}")
    private String markdownRoot;

    @Value("${bridge.search-v3.sparse-top-k:8}")
    private int sparseTopK;

    @Value("${bridge.search-v3.dense-top-k:8}")
    private int denseTopK;

    @Value("${bridge.search-v3.fusion-top-k:8}")
    private int fusionTopK;

    @Value("${bridge.search-v3.rerank-top-n:6}")
    private int rerankTopN;

    @Value("${bridge.search-v3.final-top-k:4}")
    private int finalTopK;

    @Value("${bridge.search-v3.rrf-k:60}")
    private int rrfK;

    public MarkdownHybridSearchV3(MarkdownChunkerV3 chunker,
                                  SqliteHybridIndexV3 sqliteIndex,
                                  BgeRerankerClient rerankerClient) {
        this.chunker = chunker;
        this.sqliteIndex = sqliteIndex;
        this.rerankerClient = rerankerClient;
    }

    public SearchResultV3 search(String query) {
        String safeQuery = query == null ? "" : query.trim();
        Path root = Paths.get(markdownRoot);
        List<MarkdownChunkV3> chunks = chunker.chunkDirectory(root);
        SqliteHybridIndexV3.IndexStatus indexStatus = sqliteIndex.ensureFresh(root, chunks);

        String sparseQuery = buildFtsQuery(safeQuery);
        List<ScoredDoc> sparseHits = sparseQuery.isBlank()
                ? List.of()
                : sqliteIndex.sparseSearch(sparseQuery, sparseTopK);
        List<ScoredDoc> denseHits = safeQuery.isBlank()
                ? List.of()
                : sqliteIndex.denseSearch(safeQuery, denseTopK);
        List<ScoredDoc> fusedHits = rrfFusion(sparseHits, denseHits);

        List<ScoredDoc> rerankCandidates = fusedHits.subList(0, Math.min(rerankTopN, fusedHits.size()));
        List<ScoredDoc> rerankedHits = rerankCandidates.isEmpty()
                ? List.of()
                : rerankerClient.rerank(safeQuery, rerankCandidates, finalTopK).stream()
                .map(doc -> enrichRerankMetadata(doc, fusedHits))
                .toList();

        List<SearchTraceV3> traces = List.of(
                new SearchTraceV3("SPARSE_FTS5_BM25", sparseQuery, sparseHits.size()),
                new SearchTraceV3("DENSE_VECTOR", safeQuery, denseHits.size()),
                new SearchTraceV3("RRF_FUSION", safeQuery, fusedHits.size()),
                new SearchTraceV3("RERANK", safeQuery, rerankedHits.size())
        );

        return new SearchResultV3(
                safeQuery,
                indexStatus.rebuilt(),
                indexStatus.chunkCount(),
                traces,
                sparseHits,
                denseHits,
                fusedHits,
                rerankedHits
        );
    }

    private List<ScoredDoc> rrfFusion(List<ScoredDoc> sparseHits, List<ScoredDoc> denseHits) {
        Map<String, Double> fusedScores = new LinkedHashMap<>();
        Map<String, ScoredDoc> docMap = new LinkedHashMap<>();

        addRrfScores(fusedScores, docMap, sparseHits, "V3_FUSED_SPARSE");
        addRrfScores(fusedScores, docMap, denseHits, "V3_FUSED_DENSE");

        return fusedScores.entrySet().stream()
                .map(entry -> docMap.get(entry.getKey()).withScore(entry.getValue()).withSource("V3_FUSED"))
                .sorted(Comparator.comparingDouble(ScoredDoc::score).reversed())
                .limit(fusionTopK)
                .toList();
    }

    private void addRrfScores(Map<String, Double> fusedScores,
                              Map<String, ScoredDoc> docMap,
                              List<ScoredDoc> docs,
                              String sourceTag) {
        for (int i = 0; i < docs.size(); i++) {
            ScoredDoc doc = docs.get(i);
            docMap.putIfAbsent(doc.id(), mergeSourceMetadata(doc, sourceTag));
            fusedScores.merge(doc.id(), 1.0 / (rrfK + i + 1), Double::sum);
        }
    }

    private ScoredDoc mergeSourceMetadata(ScoredDoc doc, String sourceTag) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (doc.metadata() != null) {
            metadata.putAll(doc.metadata());
        }
        metadata.put("fusionSource", sourceTag);
        return new ScoredDoc(doc.id(), doc.content(), doc.source(), doc.score(), metadata);
    }

    private ScoredDoc enrichRerankMetadata(ScoredDoc rerankedDoc, List<ScoredDoc> fusedHits) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (rerankedDoc.metadata() != null) {
            metadata.putAll(rerankedDoc.metadata());
        }

        for (ScoredDoc fusedDoc : fusedHits) {
            if (fusedDoc.id().equals(rerankedDoc.id())) {
                metadata.put("rrfScore", fusedDoc.score());
                break;
            }
        }
        return new ScoredDoc(
                rerankedDoc.id(),
                rerankedDoc.content(),
                rerankedDoc.source(),
                rerankedDoc.score(),
                metadata
        );
    }

    /**
     * 生成 FTS5 MATCH 查询。
     *
     * <p>设计意图：不直接拿原始中文长句去 MATCH，
     * 而是先走 jieba 分词文本，再拼 OR 查询，提高中文关键词召回率。</p>
     */
    private String buildFtsQuery(String query) {
        String tokenized = chunker.tokenizeForFts(query);
        if (tokenized.isBlank()) {
            return "";
        }

        String[] tokens = tokenized.split("\\s+");
        List<String> terms = new ArrayList<>();
        for (String token : tokens) {
            if (!token.isBlank()) {
                terms.add("\"" + token.replace("\"", "") + "\"");
            }
        }
        return String.join(" OR ", terms);
    }
}
