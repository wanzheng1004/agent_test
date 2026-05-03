package com.bridge.agent.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 病害记录实体（同时存储原始描述和规范化描述）
 */
@Data
@Entity
@Table(name = "defect_record")
public class DefectRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bridge_id", nullable = false, length = 50)
    private String bridgeId;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(name = "inspection_date", nullable = false)
    private LocalDate inspectionDate;

    @Column(length = 100)
    private String component;       // 病害构件

    @Column(name = "defect_type", length = 50)
    private String defectType;      // 病害类型

    @Column(columnDefinition = "TEXT")
    private String description;     // 规范化描述（Agent 生成）

    private Integer grade;          // 病害等级 1-4

    @Column(name = "standard_ref", length = 100)
    private String standardRef;     // 规范条款引用

    @Column(name = "grade_reason", columnDefinition = "TEXT")
    private String gradeReason;     // 定级依据

    @Column(length = 20)
    private String urgency;         // 处置紧迫度

    @Column(name = "raw_description", columnDefinition = "TEXT")
    private String rawDescription;  // 检测员原始输入

    @Column(columnDefinition = "JSON")
    private String photos;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
