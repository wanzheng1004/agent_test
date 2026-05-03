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

    /** 按会话 ID 查询（检修后 Agent 汇总本次记录） */
    List<DefectRecord> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /** 按桥梁 ID 查最近 N 条（供通用查询使用） */
    List<DefectRecord> findByBridgeIdOrderByInspectionDateDescCreatedAtDesc(String bridgeId);

    /**
     * MySQL 全文检索（BM25 稀疏检索路径）
     * 使用 AGAINST 在 description + defect_type + component 字段中检索
     */
    @Query(value = """
            SELECT * FROM defect_record
            WHERE bridge_id = :bridgeId
              AND MATCH(description, defect_type, component)
                  AGAINST(:keyword IN BOOLEAN MODE)
            ORDER BY MATCH(description, defect_type, component)
                     AGAINST(:keyword IN BOOLEAN MODE) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<DefectRecord> fullTextSearch(@Param("bridgeId") String bridgeId,
                                       @Param("keyword") String keyword,
                                       @Param("limit") int limit);

    /**
     * 规范文档全文检索（在所有记录中检索，不限 bridgeId）
     * 用于 RAG 管道的 BM25 稀疏检索
     */
    @Query(value = """
            SELECT * FROM defect_record
            WHERE MATCH(description, defect_type, component)
                  AGAINST(:keyword IN BOOLEAN MODE)
            ORDER BY MATCH(description, defect_type, component)
                     AGAINST(:keyword IN BOOLEAN MODE) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<DefectRecord> fullTextSearchGlobal(@Param("keyword") String keyword,
                                             @Param("limit") int limit);
}
