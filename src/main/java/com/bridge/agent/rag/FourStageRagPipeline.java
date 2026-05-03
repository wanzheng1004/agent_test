package com.bridge.agent.rag;

import com.bridge.agent.repository.DefectRecordRepository;
import com.bridge.agent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 四阶段 RAG 管道 —— 显式分阶段实现
 *
 * <p>Stage 1: 查询改写（可选，短查询自动跳过）
 * <p>Stage 2: 双路检索（并行）
 *   - 路径A（稀疏）：MySQL FULLTEXT BM25 检索
 *   - 路径B（稠密）：Qdrant 向量语义检索
 * <p>Stage 3: RRF 融合（Reciprocal Rank Fusion）
 * <p>Stage 4: LLM Reranker 精排
 *
 * <p>面试要点：
 * <ul>
 *   <li>为什么用 RRF 不用分数加权？
 *       BM25 分数和余弦相似度不在同一尺度，直接加权没有意义。
 *       RRF 只看排名不看分数，天然解决尺度不一致问题。</li>
 *   <li>为什么不用 Spring AI 的 QuestionAnswerAdvisor 封装？
 *       它把检索、融合、重排全封装了，无法分阶段调优，
 *       也无法保留每阶段中间结果用于分析。</li>
 *   <li>BM25（精确匹配条款号）+ 向量（语义相关）互补：
 *       检索员说"JTG/T H21-2011 6.3.2"→ BM25 精确命中；
 *       检索员说"桥墩裂缝怎么定级"→ 向量检索语义匹配。</li>
 * </ul>
 */
@Service
public class FourStageRagPipeline {

    private static final Logger log = LoggerFactory.getLogger(FourStageRagPipeline.class);

    /** RRF 融合参数 k=60 是标准值，实测 40-60 差异不大 */
    private static final int RRF_K = 60;

    private final VectorStore vectorStore;
    private final DefectRecordRepository defectRepo;
    private final ChatClient chatClient;

    @Value("${bridge.agent.rag.sparse-top-k:20}")
    private int sparseTopK;

    @Value("${bridge.agent.rag.dense-top-k:20}")
    private int denseTopK;

    @Value("${bridge.agent.rag.rerank-top-n:5}")
    private int rerankTopN;

    @Value("${bridge.agent.rag.final-top-k:3}")
    private int finalTopK;

    public FourStageRagPipeline(VectorStore vectorStore,
                                  DefectRecordRepository defectRepo,
                                  ChatClient.Builder builder) {
        this.vectorStore = vectorStore;
        this.defectRepo = defectRepo;
        this.chatClient = builder.build();
    }

    /**
     * 执行完整四阶段管道，返回分阶段结果
     */
    public RagResult retrieve(String query) {
        RagResult result = new RagResult(query);
        log.debug("RAG pipeline started: query={}", query);

        // ==========================================
        // Stage 1: 查询改写（短查询跳过）
        // ==========================================
        String searchQuery = query;
        if (needsRewrite(query)) {
            searchQuery = rewriteQuery(query);
            result.setRewrittenQuery(searchQuery);
            log.debug("Query rewritten: {} → {}", query, searchQuery);
        }

        final String finalQuery = searchQuery;

        // ==========================================
        // Stage 2: 双路检索（并行执行）
        // ==========================================
        long sparseStart = System.currentTimeMillis();
        long denseStart  = System.currentTimeMillis();

        CompletableFuture<List<ScoredDoc>> sparseFuture =
                CompletableFuture.supplyAsync(() -> sparseSearch(finalQuery));
        CompletableFuture<List<ScoredDoc>> denseFuture =
                CompletableFuture.supplyAsync(() -> denseSearch(finalQuery));

        CompletableFuture.allOf(sparseFuture, denseFuture).join();

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
        List<ScoredDoc> fused = rrfFusion(result.getSparseResults(), result.getDenseResults());
        result.setFusedResults(fused);
        result.setFusionLatencyMs(System.currentTimeMillis() - fusionStart);

        log.debug("Stage 3 RRF done: fused={}", fused.size());

        // ==========================================
        // Stage 4: LLM Reranker 精排
        //   输入 Top-N，输出 Top-finalTopK
        // ==========================================
        long rerankStart = System.currentTimeMillis();
        List<ScoredDoc> toRerank = fused.subList(0, Math.min(rerankTopN, fused.size()));
        List<ScoredDoc> reranked = llmRerank(query, toRerank);
        result.setFinalResults(reranked);
        result.setRerankLatencyMs(System.currentTimeMillis() - rerankStart);

        log.info("RAG pipeline done: {}", result.toDebugSummary());
        return result;
    }

    // =====================================================
    // Stage 2a: 稀疏检索（MySQL FULLTEXT BM25）
    // =====================================================

    /**
     * MySQL FULLTEXT 全文检索（BM25 稀疏路径）。
     *
     * <p>面试要点：为什么用 MySQL FULLTEXT 而非 Elasticsearch？
     * → 现有系统已有 MySQL，引入 ES 增加运维负担。
     *   MySQL 8.0 的 FULLTEXT + ngram 对中文有基础支持，
     *   配合 Boolean Mode 可以做关键词精确匹配（条款号如 "6.3.2"）。
     */
    private List<ScoredDoc> sparseSearch(String query) {
        // 对 MySQL FULLTEXT，用 + 操作符做必须出现的关键词检索
        String booleanQuery = Arrays.stream(query.split("\\s+"))
                .filter(w -> w.length() >= 2)
                .map(w -> "+" + w + "*")
                .collect(Collectors.joining(" "));
        if (booleanQuery.isBlank()) booleanQuery = query;

        return defectRepo.fullTextSearchGlobal(booleanQuery, sparseTopK).stream()
                .map(defect -> new ScoredDoc(
                        "defect-" + defect.getId(),
                        formatDefectAsDoc(defect),
                        "SPARSE",
                        1.0, // MySQL FULLTEXT 不返回原始分数，统一设为1.0（RRF 只看排名）
                        Map.of("type", "defect_record",
                               "bridgeId", defect.getBridgeId() != null ? defect.getBridgeId() : "",
                               "defectType", defect.getDefectType() != null ? defect.getDefectType() : "")
                ))
                .collect(Collectors.toList());
    }

    private String formatDefectAsDoc(com.bridge.agent.entity.DefectRecord d) {
        return String.format("【%s】%s\n病害类型：%s，等级：%s类，规范依据：%s\n%s",
                d.getComponent(), d.getDescription(),
                d.getDefectType(), d.getGrade(), d.getStandardRef(),
                d.getGradeReason() != null ? d.getGradeReason() : "");
    }

    // =====================================================
    // Stage 2b: 稠密检索（Qdrant 向量语义检索）
    // =====================================================

    /**
     * Qdrant 向量检索（稠密语义路径）。
     *
     * <p>使用 Spring AI VectorStore 统一抽象，底层换成 PGVector 或其他
     * 向量库时，这里代码无需修改。
     */
    private List<ScoredDoc> denseSearch(String query) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(denseTopK)
                .build();

        List<Document> docs = vectorStore.similaritySearch(request);

        return IntStream.range(0, docs.size())
                .mapToObj(i -> {
                    Document doc = docs.get(i);
                    return new ScoredDoc(
                            doc.getId(),
                            doc.getText(),
                            "DENSE",
                            1.0 - (double) i / docs.size(), // 排名越靠前分数越高
                            doc.getMetadata()
                    );
                })
                .collect(Collectors.toList());
    }

    // =====================================================
    // Stage 3: RRF 融合
    // =====================================================

    /**
     * Reciprocal Rank Fusion 融合稀疏和稠密检索结果。
     *
     * <p>RRF 公式：score(d) = Σ 1/(k + rank(d, list))
     *
     * <p>面试要点：
     * "RRF 只看每个文档在各路排名中的位置，不看具体分数。
     *  这解决了 BM25 分数（正整数）和余弦相似度（0-1浮点）量纲不同的问题，
     *  无需任何标准化，直接混排。k=60 是实验证明的稳定值。"
     */
    private List<ScoredDoc> rrfFusion(List<ScoredDoc> sparse, List<ScoredDoc> dense) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, ScoredDoc> docMap = new HashMap<>();

        // 稀疏路径贡献分数
        for (int i = 0; i < sparse.size(); i++) {
            String id = sparse.get(i).id();
            scores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            docMap.putIfAbsent(id, sparse.get(i));
        }

        // 稠密路径贡献分数
        for (int i = 0; i < dense.size(); i++) {
            String id = dense.get(i).id();
            scores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            docMap.putIfAbsent(id, dense.get(i));
        }

        // 按融合分数降序，返回带 FUSED 来源标记的文档
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> docMap.get(e.getKey()).withScore(e.getValue())
                        .withSource("FUSED"))
                .collect(Collectors.toList());
    }

    // =====================================================
    // Stage 4: LLM Reranker 精排
    // =====================================================

    /**
     * 使用 LLM 作为 listwise reranker，对候选文档重新排序。
     *
     * <p>面试要点：
     * "Reranker 有两种选择：
     *  (1) 部署 cross-encoder 模型（如 bge-reranker-v2-m3），速度快但需要额外部署；
     *  (2) LLM-as-Reranker，让 LLM 判断每个候选文档与查询的相关性。
     *  我们选择 LLM-as-Reranker，免部署，且对中文专业术语理解更好。
     *  代价是多一次 LLM 调用，但 finalTopK=3 时候选文档很少，延迟可接受。"
     */
    private List<ScoredDoc> llmRerank(String originalQuery, List<ScoredDoc> candidates) {
        if (candidates.size() <= 1) return candidates;

        // 构造候选文档描述（带编号）
        StringBuilder candidateText = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            candidateText.append(String.format("[%d] %s\n\n", i, candidates.get(i).content()));
        }

        String prompt = String.format("""
                查询：%s

                候选文档：
                %s

                请按与查询的相关性从高到低排序，返回文档编号数组（JSON）。
                只返回JSON，不要解释。格式：{"rankedIndices": [2, 0, 1]}
                """, originalQuery, candidateText);

        try {
            String response = chatClient.prompt()
                    .system("你是桥梁规范文档检索结果排序专家。")
                    .user(prompt)
                    .call()
                    .content();

            // 解析排序结果
            String json = extractJson(response);
            int[] rankedIndices = parseRankedIndices(json, candidates.size());

            return Arrays.stream(rankedIndices)
                    .filter(idx -> idx >= 0 && idx < candidates.size())
                    .limit(finalTopK)
                    .mapToObj(candidates::get)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("LLM reranking failed, fallback to RRF order: {}", e.getMessage());
            return candidates.subList(0, Math.min(finalTopK, candidates.size()));
        }
    }

    // =====================================================
    // Stage 1: 查询改写（可选）
    // =====================================================

    private boolean needsRewrite(String query) {
        // 短查询或不含关键词时触发改写（超过 10 字的查询通常无需改写）
        return query.length() < 8 || query.split("\\s+").length < 2;
    }

    private String rewriteQuery(String query) {
        return chatClient.prompt()
                .system("将以下桥梁检测相关的短查询扩展为更完整的搜索描述（不超过20字），保持专业术语，只输出改写后的查询文本。")
                .user(query)
                .call()
                .content().trim();
    }

    // =====================================================
    // 工具方法
    // =====================================================

    private String extractJson(String response) {
        if (response == null) return "{}";
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        return (start >= 0 && end > start) ? response.substring(start, end + 1) : "{}";
    }

    private int[] parseRankedIndices(String json, int maxSize) {
        try {
            String indicesStr = JsonUtil.getString(json, "rankedIndices");
            // 简单处理：解析数组
            String cleaned = json.replaceAll("[^0-9,]", "").replaceAll(",+", ",")
                    .replaceAll("^,|,$", "");
            if (cleaned.isEmpty()) return IntStream.range(0, maxSize).toArray();
            return Arrays.stream(cleaned.split(","))
                    .mapToInt(Integer::parseInt).toArray();
        } catch (Exception e) {
            return IntStream.range(0, maxSize).toArray();
        }
    }
}
