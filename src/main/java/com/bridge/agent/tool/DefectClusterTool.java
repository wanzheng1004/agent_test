package com.bridge.agent.tool;

import com.bridge.agent.entity.DefectRecord;
import com.bridge.agent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具：历史病害语义聚类（高频病害分析）
 *
 * <p>工具名：cluster_defects
 * <p>输入：{"bridgeId": "BRG-001", "months": 36}（或直接传入病害描述列表）
 * <p>输出：聚类后的高频病害排行文本
 *
 * <p>解决"墩柱裂缝""桥墩裂缝""主墩开裂"被当作三条独立记录的问题。
 * 通过语义聚类将同类病害合并，输出真正有参考价值的高频病害排行。
 *
 * <p>面试要点：
 * "聚类算法用简单的贪心合并（不用 K-Means/DBSCAN）：
 *  遍历所有描述的向量，与已有簇中心计算余弦相似度，
 *  >0.85 则合入同一簇，否则新建簇。
 *  这对少量数据（几十条历史记录）已经足够准确，
 *  而且无需预设 K 值。"
 */
@Service
public class DefectClusterTool {

    private static final Logger log = LoggerFactory.getLogger(DefectClusterTool.class);
    private static final double SIMILARITY_THRESHOLD = 0.85;

    private final EmbeddingModel embeddingModel;
    private final DefectHistoryTool defectHistoryTool;

    public DefectClusterTool(EmbeddingModel embeddingModel,
                              DefectHistoryTool defectHistoryTool) {
        this.embeddingModel = embeddingModel;
        this.defectHistoryTool = defectHistoryTool;
    }

    public String execute(String jsonInput) {
        String bridgeId = JsonUtil.getString(jsonInput, "bridgeId");
        Integer months  = JsonUtil.getInt(jsonInput, "months");
        if (months == null) months = 36;

        if (bridgeId == null || bridgeId.isBlank()) {
            return "错误：bridgeId 不能为空";
        }

        List<DefectRecord> records = defectHistoryTool.getRecords(bridgeId, months);
        if (records.isEmpty()) {
            return "桥梁 [" + bridgeId + "] 近 " + months + " 个月无历史病害记录，无法聚类分析";
        }

        List<String> descriptions = records.stream()
                .map(r -> r.getDescription() != null ? r.getDescription() : r.getRawDescription())
                .filter(d -> d != null && !d.isBlank())
                .collect(Collectors.toList());

        if (descriptions.isEmpty()) {
            return "病害记录无有效描述文本，无法聚类";
        }

        List<DefectCluster> clusters = cluster(descriptions, records);

        return formatClusters(clusters, bridgeId, months);
    }

    /**
     * 核心聚类算法：贪心合并（余弦相似度 > 0.85 视为同类）
     */
    private List<DefectCluster> cluster(List<String> descriptions,
                                         List<DefectRecord> records) {
        // Step 1: 批量向量化
        List<float[]> embeddings = embeddingModel.embed(descriptions);

        // Step 2: 贪心聚类（不需要预设 K 值）
        List<DefectCluster> clusters = new ArrayList<>();
        boolean[] assigned = new boolean[descriptions.size()];

        for (int i = 0; i < descriptions.size(); i++) {
            if (assigned[i]) continue;

            // 尝试合入已有簇
            boolean merged = false;
            for (DefectCluster cluster : clusters) {
                double sim = cosineSimilarity(embeddings.get(i), cluster.centroid);
                if (sim >= SIMILARITY_THRESHOLD) {
                    cluster.addMember(descriptions.get(i), records.get(i), embeddings.get(i));
                    assigned[i] = true;
                    merged = true;
                    break;
                }
            }

            // 建立新簇
            if (!merged) {
                DefectCluster newCluster = new DefectCluster(descriptions.get(i),
                        records.get(i), embeddings.get(i));
                clusters.add(newCluster);
                assigned[i] = true;
            }
        }

        // Step 3: 按簇大小降序排列（频次高的排前面）
        clusters.sort((a, b) -> b.members.size() - a.members.size());
        return clusters;
    }

    private String formatClusters(List<DefectCluster> clusters, String bridgeId, int months) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("桥梁 [%s] 近 %d 个月高频病害分析（共 %d 类）：\n\n",
                bridgeId, months, clusters.size()));

        for (int i = 0; i < Math.min(clusters.size(), 5); i++) { // 最多展示 Top5
            DefectCluster c = clusters.get(i);
            String lastDate = c.lastRecord.getInspectionDate() != null
                    ? c.lastRecord.getInspectionDate().toString() : "未知";
            int maxGrade = c.members.stream()
                    .map(m -> m.grade != null ? m.grade : 0)
                    .max(Integer::compareTo).orElse(0);

            sb.append(String.format("TOP%d 【%s】\n", i + 1, c.representative));
            sb.append(String.format("  出现次数：%d次 | 最近一次：%s | 当前最高等级：%s类\n",
                    c.members.size(), lastDate, maxGrade > 0 ? maxGrade : "未知"));
            if (c.members.size() > 1) {
                sb.append("  涉及构件：").append(
                        c.members.stream().map(m -> m.component).distinct()
                                .filter(Objects::nonNull)
                                .collect(Collectors.joining("、"))).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return (normA == 0 || normB == 0) ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** 聚类簇 */
    private static class DefectCluster {
        String representative; // 代表性描述（簇中第一条）
        float[] centroid;      // 簇中心向量（均值向量）
        List<DefectRecord> members = new ArrayList<>();
        DefectRecord lastRecord;

        DefectCluster(String desc, DefectRecord record, float[] embedding) {
            this.representative = desc;
            this.centroid = embedding.clone();
            this.members.add(record);
            this.lastRecord = record;
        }

        void addMember(String desc, DefectRecord record, float[] embedding) {
            members.add(record);
            // 更新簇中心（均值）
            for (int i = 0; i < centroid.length; i++) {
                centroid[i] = (centroid[i] * (members.size() - 1) + embedding[i]) / members.size();
            }
            // 更新最近记录
            if (record.getInspectionDate() != null
                    && (lastRecord.getInspectionDate() == null
                        || record.getInspectionDate().isAfter(lastRecord.getInspectionDate()))) {
                lastRecord = record;
            }
        }
    }
}
