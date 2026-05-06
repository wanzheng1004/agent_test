package com.bridge.agent.rag;

import com.huaban.analysis.jieba.JiebaSegmenter;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 中文查询分词器 —— sparse 检索前置预处理
 *
 * <p>解决的问题：
 * 旧版 sparseSearch 直接按空格切 query，再拼 Boolean Query。
 * 这更像英文检索思路，对中文自然输入几乎无效，因为中文本身没有天然空格。
 *
 * <p>当前策略：
 * <ul>
 *   <li>使用 Jieba 对中文 query 做切词</li>
 *   <li>去除长度过短和纯噪声 token</li>
 *   <li>保留顺序去重，作为 MySQL FULLTEXT 的检索输入</li>
 * </ul>
 *
 * <p>面试要点：
 * "中文稀疏检索的第一步不是拼 Boolean Query，而是先把 query 切成可检索 token。
 *  分词质量直接决定 FULLTEXT 路径的召回质量。"
 */
@Component
public class ChineseQueryTokenizer {

    private final JiebaSegmenter jieba = new JiebaSegmenter();

    /**
     * 将用户原始 query 切成适合稀疏检索的 token 列表
     */
    public List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        Set<String> dedup = new LinkedHashSet<>();
        for (String raw : jieba.sentenceProcess(query)) {
            String token = normalize(raw);
            if (isUsefulToken(token)) {
                dedup.add(token);
            }
        }
        return dedup.stream().toList();
    }

    /**
     * 生成 MySQL FULLTEXT 更易消费的稀疏查询串
     *
     * <p>这里不再拼 Boolean Mode，而是交给 ngram + natural language 模式处理，
     * 避免中文查询因布尔拼接方式不合适而劣化。
     */
    public String buildSparseQuery(String query) {
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return query == null ? "" : query.trim();
        }
        return tokens.stream().collect(Collectors.joining(" "));
    }

    private boolean isUsefulToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (token.length() >= 2) {
            return true;
        }
        return token.matches("[A-Za-z0-9./#_-]{2,}");
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
