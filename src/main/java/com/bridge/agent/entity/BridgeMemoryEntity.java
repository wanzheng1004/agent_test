package com.bridge.agent.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 桥梁级记忆实体（三级记忆架构 — 桥梁级）
 *
 * <p>存储历次检测结论和持续追踪病害的发展数据点，
 * 支持跨时间维度的病害趋势追溯查询。
 */
@Data
@Entity
@Table(name = "bridge_memory")
public class BridgeMemoryEntity {

    @Id
    @Column(name = "bridge_id", length = 50)
    private String bridgeId;

    @Column(name = "last_inspection")
    private LocalDate lastInspection;

    @Column(name = "health_score", precision = 5, scale = 2)
    private BigDecimal healthScore;    // 综合健康评分 0-100

    /**
     * 持续追踪病害 JSON（含发展数据点历史）
     *
     * <p>结构示例：
     * [{
     *   "defectId": "D-2024-001",
     *   "type": "竖向裂缝",
     *   "component": "0#桥墩墩柱",
     *   "history": [{"date":"2022-03","width":0.1,"grade":1},{"date":"2024-08","width":0.3,"grade":3}],
     *   "trend": "扩展中"
     * }]
     */
    @Column(name = "tracking_defects", columnDefinition = "JSON")
    private String trackingDefects;

    /**
     * 历次检测摘要 JSON（最近 5 次）
     */
    @Column(name = "inspection_history", columnDefinition = "JSON")
    private String inspectionHistory;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
