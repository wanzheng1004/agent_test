package com.bridge.agent.rag.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 桥梁规范文档专用切片器（条款级切片）
 *
 * <p>不使用 Spring AI 默认的 TokenTextSplitter（按 token 数量硬切），
 * 而是根据桥梁规范文档的结构特点设计专用切片策略：
 *
 * <ol>
 *   <li>条款级切片：以最小条款为单位（如"5.2.3 竖向裂缝评定标准"），保证语义完整</li>
 *   <li>上下文携带：每个切片额外携带父章节标题，避免孤立条款缺失背景</li>
 *   <li>表格完整保留：评级表格不跨条款切割，整表一个切片</li>
 *   <li>条款编号作为元数据：支持 BM25 精确匹配条款号（如"6.3.2"）</li>
 * </ol>
 *
 * <p>面试要点：
 * "规范文档不能简单按字数切，因为评级表格是一个整体，
 *  切割会导致'缝宽0.2mm'和'对应等级3类'在不同切片里，检索时无法关联。
 *  条款级切片保证了检索粒度与规范结构完全对齐。"
 */
@Component
public class StandardDocSplitter {

    private static final Logger log = LoggerFactory.getLogger(StandardDocSplitter.class);

    // 匹配条款编号：1.2.3 / 5.3 / 6.3.2.1 等格式
    private static final Pattern CLAUSE_PATTERN =
            Pattern.compile("^(\\d+(\\.\\d+){1,3})\\s+(.+)", Pattern.MULTILINE);

    // 匹配表格标识（简单实现：包含 | 的多行内容）
    private static final Pattern TABLE_PATTERN =
            Pattern.compile("(\\|.+\\|[\\n\\r]+){2,}", Pattern.MULTILINE);

    /**
     * 将原始文档列表切片为条款级文档
     *
     * @param docs 由 Tika 解析的原始文档列表
     * @param standardId 规范文档标识（如 "JTG/T H21-2011"）
     * @return 条款级切片文档列表（含完整元数据）
     */
    public List<Document> split(List<Document> docs, String standardId) {
        List<Document> chunks = new ArrayList<>();

        for (Document doc : docs) {
            String content = doc.getText();
            chunks.addAll(splitContent(content, standardId));
        }

        log.info("StandardDocSplitter: {} raw docs → {} chunks, standard={}",
                docs.size(), chunks.size(), standardId);
        return chunks;
    }

    private List<Document> splitContent(String content, String standardId) {
        List<Document> result = new ArrayList<>();

        // 预先提取所有表格，整表保留
        List<String> tables = extractTables(content);
        String contentWithoutTables = TABLE_PATTERN.matcher(content).replaceAll("[表格已单独提取]");

        // 按条款编号切片
        List<ClauseBlock> blocks = extractClauseBlocks(contentWithoutTables);

        // 将提取的表格关联到对应条款
        matchTablesToClause(blocks, tables);

        // 构建 Document 切片
        String currentChapter = "";
        String currentSection = "";

        for (ClauseBlock block : blocks) {
            // 更新当前章节上下文
            if (block.level == 1) currentChapter = block.title;
            else if (block.level == 2) currentSection = block.title;

            // 构建携带上下文的切片内容
            String chunkContent = buildChunkContent(block, currentChapter, currentSection);

            // 构建元数据（供 Qdrant payload 过滤和 BM25 精确查询使用）
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("standard_id", standardId);
            metadata.put("clause", block.clauseNumber);
            metadata.put("chapter", currentChapter);
            metadata.put("section", currentSection);
            metadata.put("title", block.title);
            metadata.put("type", block.hasTable ? "table_clause" : "text");
            metadata.put("level", block.level);

            result.add(new Document(chunkContent, metadata));
        }

        return result;
    }

    private String buildChunkContent(ClauseBlock block, String chapter, String section) {
        StringBuilder sb = new StringBuilder();
        // 携带父章节上下文，避免孤立条款缺失背景
        if (!chapter.isBlank() && !chapter.equals(block.title)) {
            sb.append("【章节】").append(chapter);
            if (!section.isBlank() && !section.equals(chapter) && !section.equals(block.title)) {
                sb.append(" > ").append(section);
            }
            sb.append("\n");
        }
        sb.append("【条款 ").append(block.clauseNumber).append("】").append(block.title).append("\n");
        sb.append(block.content);
        if (block.tableContent != null) {
            sb.append("\n").append(block.tableContent);
        }
        return sb.toString();
    }

    private List<ClauseBlock> extractClauseBlocks(String content) {
        List<ClauseBlock> blocks = new ArrayList<>();
        Matcher matcher = CLAUSE_PATTERN.matcher(content);

        int lastEnd = 0;
        String lastClause = "";
        String lastTitle = "";
        int lastStart = 0;

        while (matcher.find()) {
            if (lastEnd > 0) {
                String blockContent = content.substring(lastEnd, matcher.start()).trim();
                int level = lastClause.split("\\.").length;
                blocks.add(new ClauseBlock(lastClause, lastTitle, blockContent, level));
            }
            lastClause = matcher.group(1);
            lastTitle = matcher.group(3).trim();
            lastEnd = matcher.end();
            lastStart = matcher.start();
        }

        // 最后一个条款
        if (lastEnd > 0) {
            String blockContent = content.substring(lastEnd).trim();
            int level = lastClause.split("\\.").length;
            blocks.add(new ClauseBlock(lastClause, lastTitle, blockContent, level));
        }

        return blocks;
    }

    private List<String> extractTables(String content) {
        List<String> tables = new ArrayList<>();
        Matcher matcher = TABLE_PATTERN.matcher(content);
        while (matcher.find()) {
            tables.add(matcher.group());
        }
        return tables;
    }

    private void matchTablesToClause(List<ClauseBlock> blocks, List<String> tables) {
        // 简单实现：将表格顺序分配给最近的条款块
        for (String table : tables) {
            if (!blocks.isEmpty()) {
                ClauseBlock last = blocks.get(blocks.size() - 1);
                last.tableContent = table;
                last.hasTable = true;
            }
        }
    }

    /** 条款块内部数据结构 */
    private static class ClauseBlock {
        String clauseNumber;    // 条款编号，如 "6.3.2"
        String title;           // 条款标题
        String content;         // 条款文本内容
        int level;              // 层级深度（1=章, 2=节, 3=条）
        String tableContent;    // 关联的表格内容
        boolean hasTable;

        ClauseBlock(String clauseNumber, String title, String content, int level) {
            this.clauseNumber = clauseNumber;
            this.title = title;
            this.content = content;
            this.level = level;
        }
    }
}
