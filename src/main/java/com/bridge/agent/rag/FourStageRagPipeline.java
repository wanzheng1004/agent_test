package com.bridge.agent.rag;

import com.bridge.agent.llm.OpenAiCompatibleChatService;
import com.bridge.agent.repository.DefectRecordRepository;
import com.bridge.agent.repository.DefectSearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 四阶段 RAG 管道 —— 显式分阶段实现
 *
 * <p>Stage 1: 查询改写（可选，短查询自动触发）
 * <p>Stage 2: 双路检索（并行）
 *   - 路径A（稀疏）：MySQL FULLTEXT 稀疏检索
 *   - 路径B（稠密）：Qdrant 向量语义检索
 * <p>Stage 3: RRF 融合（Reciprocal Rank Fusion）
 * <p>Stage 4: BGE Reranker 精排
 *
 * <p>本轮优化重点：
 * <ul>
 *   <li>sparse 路径使用 MySQL 返回的真实 FULLTEXT score</li>
 *   <li>接入 Jieba 中文分词，替换旧版“按空格切词 + Boolean Query”</li>
 *   <li>dense 路径优先读取真实相似度分数，拿不到再退化</li>
 *   <li>RRF 前限制候选集合大小，rerank 前做去重</li>
 *   <li>利用 bridgeId / defectType 等 metadata 做过滤和加权</li>
 * </ul>
 *
 * <p>面试要点：
 * "RAG 的关键不只是双路召回，而是把 query 信号、检索分数、metadata 过滤、
 *  融合候选控制和 rerank 前去重这些细节真正做实。否则骨架看起来完整，
 *  实际召回质量和可解释性都上不去。"
 */
@Service
public class FourStageRagPipeline {

    private static final Logger log = LoggerFactory.getLogger(FourStageRagPipeline.class);
    private static final int RRF_K = 60;
    private static final Pattern BRIDGE_ID_PATTERN = Pattern.compile("BRG-\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern STANDARD_REF_PATTERN = Pattern.compile("(JTG|JTG/T|JTG-T|6\\.\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE);
    private static final List<String> DEFECT_TYPE_KEYWORDS = List.of(
            "裂缝", "坑槽", "变形", "破损", "腐蚀", "渗水", "剥落", "露筋", "蜂窝", "麻面"
    );

    private final VectorStore vectorStore;
    private final DefectRecordRepository defectRepo;
    private final OpenAiCompatibleChatService qwenClient;
    private final BgeRerankerClient rerankerClient;
    private final ChineseQueryTokenizer queryTokenizer;

    @Value("${bridge.agent.rag.sparse-top-k:20}")
    private int sparseTopK;

    @Value("${bridge.agent.rag.dense-top-k:20}")
    private int denseTopK;

    @Value("${bridge.agent.rag.fusion-top-k:12}")
    private int fusionTopK;

    @Value("${bridge.agent.rag.rerank-top-n:5}")
    private int rerankTopN;

    @Value("${bridge.agent.rag.final-top-k:3}")
    private int finalTopK;

    public FourStageRagPipeline(VectorStore vectorStore,
                                DefectRecordRepository defectRepo,
                                OpenAiCompatibleChatService qwenClient,
                                BgeRerankerClient rerankerClient,
                                ChineseQueryTokenizer queryTokenizer) {
        this.vectorStore = vectorStore;
        this.defectRepo = defectRepo;
        this.qwenClient = qwenClient;
        this.rerankerClient = rerankerClient;
        this.queryTokenizer = queryTokenizer;
    }

    /**
     * 执行完整四阶段流程，返回分阶段结果
     */
    public RagResult retrieve(String query) {
        RagResult result = new RagResult(query);
        QuerySignals signals = analyzeQuery(query);
        log.debug("RAG pipeline started: query={}, signals={}", query, signals);

        // ==========================================
        // Stage 1: 查询改写（短查询触发）
        // ==========================================
        String searchQuery = query;
        if (needsRewrite(query, signals)) {
            searchQuery = rewriteQuery(query);
            result.setRewrittenQuery(searchQuery);
            signals = analyzeQuery(searchQuery);
            log.debug("Query rewritten: {} -> {}", query, searchQuery);
        }

        final String finalQuery = searchQuery;
        final QuerySignals finalSignals = signals;

        // ==========================================
        // Stage 2: 双路检索（并行执行）
        // ==========================================
        long sparseStart = System.currentTimeMillis();
        long denseStart = System.currentTimeMillis();

        var sparseFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> sparseSearch(finalQuery, finalSignals));
        var denseFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> denseSearch(finalQuery, finalSignals));

        java.util.concurrent.CompletableFuture.allOf(sparseFuture, denseFuture).join();

        result.setSparseResults(sparseFuture.join());
        result.setSparseLatencyMs(System.currentTimeMillis() - sparseStart);
        result.setDenseResults(denseFuture.join());
        result.setDenseLatencyMs(System.currentTimeMillis() - denseStart);

        log.debug("Stage 2 done: sparse={}, dense={}",
                result.getSparseResults().size(), result.getDenseResults().size());

        // ==========================================
        // Stage 3: RRF 融合
        // ==========================================
        long fusionStart = System.currentTimeMillis();
        List<ScoredDoc> fused = rrfFusion(result.getSparseResults(), result.getDenseResults(), finalSignals);
        result.setFusedResults(fused);
        result.setFusionLatencyMs(System.currentTimeMillis() - fusionStart);

        log.debug("Stage 3 RRF done: fused={}", fused.size());

        // ==========================================
        // Stage 4: Reranker 精排
        // 输入去重后的 Top-N，输出 Top-finalTopK
        // ==========================================
        long rerankStart = System.currentTimeMillis();
        List<ScoredDoc> deduplicated = deduplicateDocs(fused);
        List<ScoredDoc> boosted = applyMetadataBoost(deduplicated, finalSignals);
        List<ScoredDoc> rerankCandidates = boosted.subList(0, Math.min(rerankTopN, boosted.size()));
        List<ScoredDoc> reranked = rerankerClient.rerank(query, rerankCandidates, finalTopK);
        result.setFinalResults(reranked);
        result.setRerankLatencyMs(System.currentTimeMillis() - rerankStart);

        log.info("RAG pipeline done: {}", result.toDebugSummary());
        return result;
    }

    // =====================================================
    // Stage 2a: 稀疏检索（MySQL FULLTEXT）
    // =====================================================

    /**
     * MySQL FULLTEXT 稀疏检索路径
     *
     * <p>本轮优化点：
     * <ol>
     *   <li>先用 Jieba 对中文 query 分词，再生成稀疏查询串</li>
     *   <li>改用 NATURAL LANGUAGE MODE，而不是 Boolean Mode</li>
     *   <li>保留数据库返回的真实 FULLTEXT score</li>
     *   <li>当 query 中明确包含 bridgeId 时，直接走桥梁过滤版查询</li>
     * </ol>
     */
    private List<ScoredDoc> sparseSearch(String query, QuerySignals signals) {
        String sparseQuery = queryTokenizer.buildSparseQuery(query);
        List<String> tokens = queryTokenizer.tokenize(query);

        log.debug("Sparse query built: original='{}', sparse='{}', tokens={}",
                query, sparseQuery, tokens);

        List<DefectSearchHit> hits = signals.bridgeId() != null
                ? defectRepo.fullTextSearch(signals.bridgeId(), sparseQuery, sparseTopK)
                : defectRepo.fullTextSearchGlobal(sparseQuery, sparseTopK);

        return hits.stream()
                .map(this::toSparseDoc)
                .map(doc -> applyMetadataBoost(doc, signals))
                .sorted(Comparator.comparingDouble(ScoredDoc::score).reversed())
                .limit(sparseTopK)
                .collect(Collectors.toList());
    }

    private ScoredDoc toSparseDoc(DefectSearchHit hit) {
        return new ScoredDoc(
                "defect-" + hit.getId(),
                formatDefectAsDoc(hit),
                "SPARSE",
                hit.getScore() != null ? hit.getScore() : 0.0,
                Map.of(
                        "type", "defect_record",
                        "bridgeId", safe(hit.getBridgeId()),
                        "defectType", safe(hit.getDefectType()),
                        "standardRef", safe(hit.getStandardRef())
                )
        );
    }

    private String formatDefectAsDoc(DefectSearchHit defect) {
        return String.format("【%s】%s%n病害类型：%s，等级：%s类，规范依据：%s%n%s",
                defect.getComponent(),
                defect.getDescription(),
                defect.getDefectType(),
                defect.getGrade(),
                defect.getStandardRef(),
                defect.getGradeReason() != null ? defect.getGradeReason() : "");
    }

    // =====================================================
    // Stage 2b: 稠密检索（Qdrant 向量语义检索）
    // =====================================================

    /**
     * Qdrant 向量检索（稠密语义路径）
     *
     * <p>本轮优化点：
     * <ul>
     *   <li>优先从 metadata 中读取真实相似度/距离分数</li>
     *   <li>拿不到时再退化为“按排名模拟分数”</li>
     *   <li>召回后也应用 bridgeId / defectType metadata 加权</li>
     * </ul>
     */
    private List<ScoredDoc> denseSearch(String query, QuerySignals signals) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(denseTopK)
                .build();

        List<Document> docs = vectorStore.similaritySearch(request);

        List<ScoredDoc> scored = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            double score = extractDenseScore(doc, i, docs.size());
            ScoredDoc scoredDoc = new ScoredDoc(
                    doc.getId(),
                    doc.getText(),
                    "DENSE",
                    score,
                    doc.getMetadata()
            );
            scored.add(applyMetadataBoost(scoredDoc, signals));
        }

        scored.sort(Comparator.comparingDouble(ScoredDoc::score).reversed());
        return scored;
    }

    private double extractDenseScore(Document doc, int rankIndex, int totalSize) {
        if (doc.getMetadata() != null) {
            Double score = getDouble(doc.getMetadata(), "score");
            if (score == null) score = getDouble(doc.getMetadata(), "similarity");
            if (score == null) score = getDouble(doc.getMetadata(), "relevance");
            if (score != null) {
                return score;
            }

            Double distance = getDouble(doc.getMetadata(), "distance");
            if (distance == null) distance = getDouble(doc.getMetadata(), "_distance");
            if (distance != null) {
                return 1.0 / (1.0 + distance);
            }
        }

        if (totalSize <= 0) {
            return 0.0;
        }
        return 1.0 - (double) rankIndex / totalSize;
    }

    // =====================================================
    // Stage 3: RRF 融合
    // =====================================================

    /**
     * Reciprocal Rank Fusion 融合稀疏和稠密结果
     *
     * <p>本轮优化点：
     * <ul>
     *   <li>限制每一路参与融合的候选集合大小</li>
     *   <li>融合后继续应用 metadata 加权，确保结构化信号能影响排序</li>
     * </ul>
     */
    private List<ScoredDoc> rrfFusion(List<ScoredDoc> sparse, List<ScoredDoc> dense, QuerySignals signals) {
        List<ScoredDoc> sparseLimited = sparse.subList(0, Math.min(fusionTopK, sparse.size()));
        List<ScoredDoc> denseLimited = dense.subList(0, Math.min(fusionTopK, dense.size()));

        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, ScoredDoc> docMap = new HashMap<>();

        for (int i = 0; i < sparseLimited.size(); i++) {
            String id = sparseLimited.get(i).id();
            scores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            docMap.putIfAbsent(id, sparseLimited.get(i));
        }

        for (int i = 0; i < denseLimited.size(); i++) {
            String id = denseLimited.get(i).id();
            scores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            docMap.putIfAbsent(id, denseLimited.get(i));
        }

        List<ScoredDoc> fused = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> docMap.get(entry.getKey())
                        .withScore(entry.getValue())
                        .withSource("FUSED"))
                .collect(Collectors.toList());

        return applyMetadataBoost(fused, signals);
    }

    // =====================================================
    // Query analysis / rewrite
    // =====================================================

    /**
     * 是否需要做 query rewrite
     *
     * <p>旧版规则只看“长度”和“空格词数”，对中文过于粗糙。
     * 新版综合考虑：
     * <ul>
     *   <li>是否已包含明确 bridgeId / 规范号</li>
     *   <li>分词后 token 数量是否过少</li>
     *   <li>query 是否过短、是否缺乏约束词</li>
     * </ul>
     */
    private boolean needsRewrite(String query, QuerySignals signals) {
        String normalized = query == null ? "" : query.replaceAll("\\s+", "");
        if (normalized.length() <= 4) {
            return true;
        }
        if (signals.hasStandardRef() || signals.bridgeId() != null) {
            return false;
        }
        if (signals.tokens().size() <= 2) {
            return true;
        }
        return normalized.length() < 10 && signals.defectType() == null;
    }

    private String rewriteQuery(String query) {
        return qwenClient.complete(
                "你是桥梁巡检规范检索改写助手。请将用户短查询改写为更适合规范检索的搜索表达，不超过20字，只输出改写后的查询文本。",
                query
        ).trim();
    }

    private QuerySignals analyzeQuery(String query) {
        List<String> tokens = queryTokenizer.tokenize(query);
        String normalized = query == null ? "" : query.trim();

        String bridgeId = null;
        Matcher bridgeMatcher = BRIDGE_ID_PATTERN.matcher(normalized);
        if (bridgeMatcher.find()) {
            bridgeId = bridgeMatcher.group().toUpperCase(Locale.ROOT);
        }

        String defectType = DEFECT_TYPE_KEYWORDS.stream()
                .filter(normalized::contains)
                .findFirst()
                .orElse(null);

        boolean hasStandardRef = STANDARD_REF_PATTERN.matcher(normalized).find();
        return new QuerySignals(bridgeId, defectType, hasStandardRef, tokens);
    }

    // =====================================================
    // Metadata / ranking helpers
    // =====================================================

    /**
     * 对 bridgeId / defectType 等结构化信号做加权
     *
     * <p>当前策略保持简单可解释：
     * 命中 bridgeId 权重大于 defectType，且只做轻量 boost，
     * 不让规则完全压过原始检索分数。
     */
    private ScoredDoc applyMetadataBoost(ScoredDoc doc, QuerySignals signals) {
        double boosted = doc.score();

        String bridgeId = metadataValue(doc.metadata(), "bridgeId");
        String defectType = metadataValue(doc.metadata(), "defectType");

        if (signals.bridgeId() != null && signals.bridgeId().equalsIgnoreCase(bridgeId)) {
            boosted += 0.30;
        }
        if (signals.defectType() != null && signals.defectType().equals(defectType)) {
            boosted += 0.15;
        }
        if (signals.hasStandardRef() && !metadataValue(doc.metadata(), "standardRef").isBlank()) {
            boosted += 0.05;
        }

        return doc.withScore(boosted);
    }

    private List<ScoredDoc> applyMetadataBoost(List<ScoredDoc> docs, QuerySignals signals) {
        return docs.stream()
                .map(doc -> applyMetadataBoost(doc, signals))
                .sorted(Comparator.comparingDouble(ScoredDoc::score).reversed())
                .collect(Collectors.toList());
    }

    /**
     * rerank 前去重
     *
     * <p>优先按 id 去重；如果 id 为空，再退回到 content 去重。
     * 同一 key 只保留当前分数更高的一条。
     */
    private List<ScoredDoc> deduplicateDocs(List<ScoredDoc> docs) {
        Map<String, ScoredDoc> dedup = new LinkedHashMap<>();
        for (ScoredDoc doc : docs) {
            String key = doc.id() != null && !doc.id().isBlank()
                    ? doc.id()
                    : doc.content();
            ScoredDoc existing = dedup.get(key);
            if (existing == null || doc.score() > existing.score()) {
                dedup.put(key, doc);
            }
        }
        return dedup.values().stream()
                .sorted(Comparator.comparingDouble(ScoredDoc::score).reversed())
                .collect(Collectors.toList());
    }

    private Double getDouble(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private String metadataValue(Map<String, Object> metadata, String key) {
        if (metadata == null || !metadata.containsKey(key) || metadata.get(key) == null) {
            return "";
        }
        return Objects.toString(metadata.get(key), "");
    }

    private String safe(String value) {
        return value != null ? value : "";
    }

    /**
     * 查询信号对象
     *
     * <p>把原始 query 中可提取的结构化信息集中起来，
     * 后续 sparse / dense / fusion / rerank 前加权都可以复用。
     */
    private record QuerySignals(
            String bridgeId,
            String defectType,
            boolean hasStandardRef,
            List<String> tokens
    ) {}
}
