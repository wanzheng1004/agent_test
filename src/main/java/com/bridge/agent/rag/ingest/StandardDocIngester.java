package com.bridge.agent.rag.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 规范文档 ETL 入库服务
 *
 * <p>流程：
 * <ol>
 *   <li>Apache Tika 解析 PDF/Word → 纯文本</li>
 *   <li>StandardDocSplitter 按条款级切片（含元数据）</li>
 *   <li>Spring AI VectorStore 批量向量化 + 写入 Qdrant</li>
 * </ol>
 *
 * <p>面试要点：
 * "入库时不用 Spring AI 默认的 TokenTextSplitter，
 *  而是自定义的 StandardDocSplitter，按规范文档条款结构切片。
 *  每个切片的条款编号作为 Qdrant payload，支持过滤检索；
 *  VectorStore.add() 时 Spring AI 自动调 EmbeddingModel 向量化，
 *  不需要手动管理向量。"
 */
@Service
public class StandardDocIngester {

    private static final Logger log = LoggerFactory.getLogger(StandardDocIngester.class);

    private final VectorStore vectorStore;
    private final StandardDocSplitter splitter;

    public StandardDocIngester(VectorStore vectorStore, StandardDocSplitter splitter) {
        this.vectorStore = vectorStore;
        this.splitter = splitter;
    }

    /**
     * 入库单个规范文档
     *
     * @param docResource 文档资源（PDF / Word / TXT）
     * @param standardId  规范标识，如 "JTG/T H21-2011"
     * @return 入库的切片数量
     */
    public int ingest(Resource docResource, String standardId) {
        log.info("Ingesting document: {}, standardId={}", docResource.getFilename(), standardId);

        // Step 1: Tika 解析文档（支持 PDF / Word / HTML 等格式）
        TikaDocumentReader reader = new TikaDocumentReader(docResource);
        List<Document> rawDocs = reader.get();
        log.info("Tika parsed {} raw documents", rawDocs.size());

        // Step 2: 条款级切片（自定义策略）
        List<Document> chunks = splitter.split(rawDocs, standardId);
        log.info("Split into {} chunks", chunks.size());

        // Step 3: 写入 Qdrant（Spring AI 自动向量化 + 批量写入）
        // VectorStore.add() 内部调用 EmbeddingModel.embed() 批量向量化，
        // 然后写入 Qdrant collection
        vectorStore.add(chunks);
        log.info("Successfully ingested {} chunks to Qdrant for {}", chunks.size(), standardId);

        return chunks.size();
    }

    /**
     * 批量入库多个规范文档
     */
    public int ingestBatch(List<Resource> resources, List<String> standardIds) {
        if (resources.size() != standardIds.size()) {
            throw new IllegalArgumentException("Resources and standardIds must have the same size");
        }
        int total = 0;
        for (int i = 0; i < resources.size(); i++) {
            total += ingest(resources.get(i), standardIds.get(i));
        }
        return total;
    }
}
