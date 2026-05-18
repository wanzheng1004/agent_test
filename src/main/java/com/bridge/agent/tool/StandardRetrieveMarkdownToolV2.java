package com.bridge.agent.tool;

import com.bridge.agent.rag.v2.MarkdownGrepSearchV2;
import com.bridge.agent.rag.v2.SearchAttemptV2;
import com.bridge.agent.rag.v2.SearchResultV2;
import com.bridge.agent.util.JsonUtil;
import org.springframework.stereotype.Service;

/**
 * V2 工具：Markdown + grep 轻搜索。
 *
 * <p>设计意图：
 * 这一版完全不依赖原有 RAG / MySQL / 向量库，
 * 假设规范资料已经被整理成 markdown 文件，
 * 然后直接做 grep 风格全文检索。</p>
 *
 * <p>面试要点：
 * “如果规范库本来就不大，先把资料整理成结构化 Markdown，
 * 再做可解释的文件检索，往往比直接上重型 RAG 更务实。”</p>
 */
@Service
public class StandardRetrieveMarkdownToolV2 {

    private final MarkdownGrepSearchV2 grepSearch;

    public StandardRetrieveMarkdownToolV2(MarkdownGrepSearchV2 grepSearch) {
        this.grepSearch = grepSearch;
    }

    public String execute(String jsonInput) {
        String query = JsonUtil.getString(jsonInput, "defectQuery");
        if (query == null || query.isBlank()) {
            return "错误：defectQuery 不能为空";
        }

        SearchResultV2 result = grepSearch.search(query);
        if (!result.found()) {
            return "未在 Markdown 规范库中检索到与 [" + query + "] 相关的条款，"
                    + "请补充病害类型、构件、位置或更具体的规范术语。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Markdown + grep 检索命中了以下结果：\n\n");
        for (int i = 0; i < result.hits().size(); i++) {
            sb.append(result.hits().get(i).content()).append("\n\n");
        }

        sb.append("检索轨迹：\n");
        for (SearchAttemptV2 attempt : result.attempts()) {
            sb.append(String.format("- [%s] %s -> %d hits%n",
                    attempt.stage(), attempt.query(), attempt.hitCount()));
        }
        return sb.toString();
    }
}
