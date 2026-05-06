package com.bridge.agent.repository;

import com.bridge.agent.entity.DefectRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DefectRecordRepository extends JpaRepository<DefectRecord, Long> {

    /** 查询指定桥梁指定日期范围内的病害记录 */
    List<DefectRecord> findByBridgeIdAndInspectionDateAfterOrderByInspectionDateDesc(
            String bridgeId, LocalDate afterDate);

    /** 按会话 ID 查询（巡检后 Agent 汇总本次记录） */
    List<DefectRecord> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /** 按桥梁 ID 查最近 N 条（供通用查询使用） */
    List<DefectRecord> findByBridgeIdOrderByInspectionDateDescCreatedAtDesc(String bridgeId);

    /**
     * MySQL 全文检索（指定桥梁范围）
     *
     * <p>返回 DefectSearchHit 而不是 DefectRecord：
     * 这样可以同时拿到原始字段和 MATCH ... AGAINST 的真实 score。
     */
    @Query(value = """
            SELECT
                id              AS id,
                bridge_id       AS bridgeId,
                component       AS component,
                defect_type     AS defectType,
                description     AS description,
                grade           AS grade,
                standard_ref    AS standardRef,
                grade_reason    AS gradeReason,
                MATCH(description, defect_type, component)
                    AGAINST(:keyword IN NATURAL LANGUAGE MODE) AS score
            FROM defect_record
            WHERE bridge_id = :bridgeId
              AND MATCH(description, defect_type, component)
                    AGAINST(:keyword IN NATURAL LANGUAGE MODE)
            ORDER BY score DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<DefectSearchHit> fullTextSearch(@Param("bridgeId") String bridgeId,
                                         @Param("keyword") String keyword,
                                         @Param("limit") int limit);

    /**
     * 全局全文检索（RAG 的 sparse 路径）
     *
     * <p>旧版使用 BOOLEAN MODE + 空格切词；
     * 新版改为 NATURAL LANGUAGE MODE + 中文分词后 query，
     * 更适合中文场景，也能直接拿到数据库返回的真实相关度分数。
     */
    @Query(value = """
            SELECT
                id              AS id,
                bridge_id       AS bridgeId,
                component       AS component,
                defect_type     AS defectType,
                description     AS description,
                grade           AS grade,
                standard_ref    AS standardRef,
                grade_reason    AS gradeReason,
                MATCH(description, defect_type, component)
                    AGAINST(:keyword IN NATURAL LANGUAGE MODE) AS score
            FROM defect_record
            WHERE MATCH(description, defect_type, component)
                    AGAINST(:keyword IN NATURAL LANGUAGE MODE)
            ORDER BY score DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<DefectSearchHit> fullTextSearchGlobal(@Param("keyword") String keyword,
                                               @Param("limit") int limit);
}
