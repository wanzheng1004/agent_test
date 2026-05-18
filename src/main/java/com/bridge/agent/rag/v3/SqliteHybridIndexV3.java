package com.bridge.agent.rag.v3;

import com.bridge.agent.rag.ScoredDoc;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V3 SQLite 混合索引。
 *
 * <p>职责：
 * 1. 把 Markdown chunk 持久化到 SQLite
 * 2. 建 FTS5 索引做 BM25 检索
 * 3. 存 embedding，供本地向量检索使用</p>
 *
 * <p>面试要点：
 * “V3 不再把 SQLite 只当缓存，而是把它当轻量 retrieval engine：
 * chunk、tokenized_text、embedding 都落进去，搜索时两路都从同一份 chunk 粒度出发。”</p>
 */
@Component
public class SqliteHybridIndexV3 {

    private static final String JDBC_PREFIX = "jdbc:sqlite:";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final EmbeddingModel embeddingModel;

    @Value("${bridge.search-v3.sqlite-path:target/markdown-rag-v3.db}")
    private String sqlitePath;

    @Value("${bridge.search-v3.embedding-batch-size:16}")
    private int embeddingBatchSize;

    public SqliteHybridIndexV3(ObjectMapper objectMapper, EmbeddingModel embeddingModel) {
        this.objectMapper = objectMapper;
        this.embeddingModel = embeddingModel;
    }

    public synchronized IndexStatus ensureFresh(Path markdownRoot, List<MarkdownChunkV3> chunks) {
        try {
            Path dbPath = resolveDbPath();
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }
            initSchema();

            long sourceLatestModified = latestModified(markdownRoot);
            try (Connection connection = openConnection()) {
                long indexedModified = readMetaLong(connection, "source_latest_modified");
                long indexedChunkCount = readMetaLong(connection, "chunk_count");
                boolean shouldRebuild = indexedChunkCount <= 0
                        || indexedModified < sourceLatestModified
                        || indexedChunkCount != chunks.size()
                        || countChunks(connection) <= 0;

                if (shouldRebuild) {
                    rebuildIndex(connection, sourceLatestModified, chunks);
                    return new IndexStatus(true, chunks.size());
                }
                return new IndexStatus(false, (int) indexedChunkCount);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to prepare SQLite hybrid index", e);
        }
    }

    public List<ScoredDoc> sparseSearch(String matchQuery, int limit) {
        String sql = """
                SELECT c.id, c.file_name, c.file_path, c.heading_path, c.chunk_text, c.metadata_json,
                       bm25(markdown_chunk_fts_v3) AS bm25_score
                FROM markdown_chunk_fts_v3 f
                JOIN markdown_chunk_v3 c ON c.id = f.chunk_id
                WHERE markdown_chunk_fts_v3 MATCH ?
                ORDER BY bm25_score
                LIMIT ?
                """;
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, matchQuery);
            ps.setInt(2, limit);
            return readDocs(ps.executeQuery(), "V3_SPARSE", true);
        } catch (Exception e) {
            throw new IllegalStateException("SQLite sparse search failed", e);
        }
    }

    public List<ScoredDoc> denseSearch(String query, int limit) {
        try {
            float[] queryVector = embeddingModel.embed(query);
            List<ScoredDoc> docs = new ArrayList<>();
            String sql = """
                    SELECT id, file_name, file_path, heading_path, chunk_text, metadata_json, embedding_json
                    FROM markdown_chunk_v3
                    """;
            try (Connection connection = openConnection();
                 PreparedStatement ps = connection.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    float[] chunkVector = objectMapper.readValue(rs.getString("embedding_json"), float[].class);
                    double cosine = cosineSimilarity(queryVector, chunkVector);
                    Map<String, Object> metadata = readMetadata(rs.getString("metadata_json"));
                    metadata.put("fileName", rs.getString("file_name"));
                    metadata.put("filePath", rs.getString("file_path"));
                    metadata.put("headingPath", rs.getString("heading_path"));
                    metadata.put("cosineScore", cosine);

                    docs.add(new ScoredDoc(
                            rs.getString("id"),
                            rs.getString("chunk_text"),
                            "V3_DENSE",
                            cosine,
                            metadata
                    ));
                }
            }
            docs.sort(Comparator.comparingDouble(ScoredDoc::score).reversed());
            return docs.stream().limit(limit).toList();
        } catch (Exception e) {
            throw new IllegalStateException("SQLite dense search failed", e);
        }
    }

    private void rebuildIndex(Connection connection,
                              long sourceLatestModified,
                              List<MarkdownChunkV3> chunks) throws Exception {
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM markdown_chunk_fts_v3");
                statement.executeUpdate("DELETE FROM markdown_chunk_v3");
                statement.executeUpdate("DELETE FROM markdown_chunk_meta_v3");
            }

            String insertChunkSql = """
                    INSERT INTO markdown_chunk_v3 (
                        id, file_name, file_path, heading_path, chunk_text, tokenized_text, metadata_json, embedding_json, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            String insertFtsSql = """
                    INSERT INTO markdown_chunk_fts_v3 (
                        chunk_id, file_name, heading_path, chunk_text, tokenized_text
                    ) VALUES (?, ?, ?, ?, ?)
                    """;

            long now = Instant.now().toEpochMilli();
            List<String> batchTexts = new ArrayList<>();
            List<MarkdownChunkV3> batchChunks = new ArrayList<>();

            try (PreparedStatement chunkPs = connection.prepareStatement(insertChunkSql);
                 PreparedStatement ftsPs = connection.prepareStatement(insertFtsSql)) {
                for (MarkdownChunkV3 chunk : chunks) {
                    batchChunks.add(chunk);
                    batchTexts.add(chunk.chunkText());
                    if (batchChunks.size() >= embeddingBatchSize) {
                        flushEmbeddingBatch(chunkPs, ftsPs, batchChunks, batchTexts, now);
                        batchChunks.clear();
                        batchTexts.clear();
                    }
                }
                if (!batchChunks.isEmpty()) {
                    flushEmbeddingBatch(chunkPs, ftsPs, batchChunks, batchTexts, now);
                }
            }

            writeMeta(connection, "source_latest_modified", String.valueOf(sourceLatestModified));
            writeMeta(connection, "chunk_count", String.valueOf(chunks.size()));
            writeMeta(connection, "last_build_at", String.valueOf(now));
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void flushEmbeddingBatch(PreparedStatement chunkPs,
                                     PreparedStatement ftsPs,
                                     List<MarkdownChunkV3> chunks,
                                     List<String> texts,
                                     long now) throws Exception {
        List<float[]> embeddings = embeddingModel.embed(texts);
        for (int i = 0; i < chunks.size(); i++) {
            MarkdownChunkV3 chunk = chunks.get(i);
            String metadataJson = objectMapper.writeValueAsString(chunk.metadata());
            String embeddingJson = objectMapper.writeValueAsString(embeddings.get(i));

            chunkPs.setString(1, chunk.id());
            chunkPs.setString(2, chunk.fileName());
            chunkPs.setString(3, chunk.filePath());
            chunkPs.setString(4, chunk.headingPath());
            chunkPs.setString(5, chunk.chunkText());
            chunkPs.setString(6, chunk.tokenizedText());
            chunkPs.setString(7, metadataJson);
            chunkPs.setString(8, embeddingJson);
            chunkPs.setLong(9, now);
            chunkPs.addBatch();

            ftsPs.setString(1, chunk.id());
            ftsPs.setString(2, chunk.fileName());
            ftsPs.setString(3, chunk.headingPath());
            ftsPs.setString(4, chunk.chunkText());
            ftsPs.setString(5, chunk.tokenizedText());
            ftsPs.addBatch();
        }
        chunkPs.executeBatch();
        ftsPs.executeBatch();
    }

    private List<ScoredDoc> readDocs(ResultSet rs, String source, boolean sparse) throws Exception {
        List<ScoredDoc> docs = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> metadata = readMetadata(rs.getString("metadata_json"));
            metadata.put("fileName", rs.getString("file_name"));
            metadata.put("filePath", rs.getString("file_path"));
            metadata.put("headingPath", rs.getString("heading_path"));
            if (sparse) {
                double rawBm25 = rs.getDouble("bm25_score");
                metadata.put("rawBm25", rawBm25);
                docs.add(new ScoredDoc(
                        rs.getString("id"),
                        rs.getString("chunk_text"),
                        source,
                        Math.max(0.0, -rawBm25),
                        metadata
                ));
            } else {
                docs.add(new ScoredDoc(
                        rs.getString("id"),
                        rs.getString("chunk_text"),
                        source,
                        rs.getDouble("score"),
                        metadata
                ));
            }
        }
        return docs;
    }

    private Map<String, Object> readMetadata(String metadataJson) throws Exception {
        Map<String, Object> metadata = objectMapper.readValue(metadataJson, MAP_TYPE);
        return new LinkedHashMap<>(metadata);
    }

    private long latestModified(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return 0L;
        }
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis();
                        } catch (Exception e) {
                            return 0L;
                        }
                    })
                    .max()
                    .orElse(0L);
        }
    }

    private int countChunks(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM markdown_chunk_v3")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void initSchema() throws Exception {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS markdown_chunk_v3 (
                        id TEXT PRIMARY KEY,
                        file_name TEXT NOT NULL,
                        file_path TEXT NOT NULL,
                        heading_path TEXT,
                        chunk_text TEXT NOT NULL,
                        tokenized_text TEXT NOT NULL,
                        metadata_json TEXT NOT NULL,
                        embedding_json TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS markdown_chunk_fts_v3
                    USING fts5(
                        chunk_id UNINDEXED,
                        file_name,
                        heading_path,
                        chunk_text,
                        tokenized_text
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS markdown_chunk_meta_v3 (
                        meta_key TEXT PRIMARY KEY,
                        meta_value TEXT NOT NULL
                    )
                    """);
        }
    }

    private void writeMeta(Connection connection, String key, String value) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO markdown_chunk_meta_v3(meta_key, meta_value)
                VALUES (?, ?)
                ON CONFLICT(meta_key) DO UPDATE SET meta_value = excluded.meta_value
                """)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private long readMetaLong(Connection connection, String key) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT meta_value FROM markdown_chunk_meta_v3 WHERE meta_key = ?
                """)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0L;
                }
                return Long.parseLong(rs.getString(1));
            }
        }
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(JDBC_PREFIX + resolveDbPath().toAbsolutePath());
    }

    private Path resolveDbPath() {
        return Paths.get(sqlitePath).toAbsolutePath().normalize();
    }

    private double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normLeft = 0.0;
        double normRight = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            normLeft += left[i] * left[i];
            normRight += right[i] * right[i];
        }
        if (normLeft == 0.0 || normRight == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normLeft) * Math.sqrt(normRight));
    }

    public record IndexStatus(boolean rebuilt, int chunkCount) {}
}
