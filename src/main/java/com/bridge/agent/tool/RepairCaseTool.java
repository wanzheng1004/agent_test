package com.bridge.agent.tool;

import com.bridge.agent.util.JsonUtil;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工具：检索历史病害处置案例
 *
 * <p>工具名：search_repair_cases
 * <p>输入：{"defectType": "裂缝", "bridgeType": "连续梁桥", "grade": 3}
 * <p>输出：历史处置方案文本
 *
 * <p>从 Qdrant 的 defect_cases collection 中检索相似处置案例，
 * 支持按病害类型、桥型进行 payload 过滤。
 */
@Service
public class RepairCaseTool {

    private final VectorStore vectorStore;

    public RepairCaseTool(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public String execute(String jsonInput) {
        String defectType  = JsonUtil.getString(jsonInput, "defectType");
        String bridgeType  = JsonUtil.getString(jsonInput, "bridgeType");
        Integer grade      = JsonUtil.getInt(jsonInput, "grade");

        if (defectType == null || defectType.isBlank()) {
            return "错误：defectType 不能为空";
        }

        // 构建语义查询
        String query = buildSearchQuery(defectType, bridgeType, grade);

        // Qdrant 向量检索（Spring AI SearchRequest 支持 payload 过滤）
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(5)
                .build();
        // 注：Spring AI 1.0.0 的 filter 语法：.withFilterExpression("defect_type == '" + defectType + "'")
        // 若 defect_cases collection 有对应 payload 字段可开启过滤

        List<Document> docs = vectorStore.similaritySearch(request);

        if (docs.isEmpty()) {
            return "未找到 [" + defectType + "] 的历史处置案例，建议参考相关规范进行处置。";
        }

        return formatCases(docs, defectType);
    }

    private String buildSearchQuery(String defectType, String bridgeType, Integer grade) {
        StringBuilder query = new StringBuilder(defectType).append(" 处置方案 修复工艺");
        if (bridgeType != null) query.append(" ").append(bridgeType);
        if (grade != null && grade >= 3) query.append(" 重要病害");
        return query.toString();
    }

    private String formatCases(List<Document> docs, String defectType) {
        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(docs.size()).append(" 条 [").append(defectType).append("] 历史处置案例：\n\n");
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            sb.append(String.format("[案例%d] %s\n\n", i + 1, doc.getText()));
        }
        return sb.toString();
    }
}
