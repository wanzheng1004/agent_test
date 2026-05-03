package com.bridge.agent.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 维修记录实体
 */
@Data
@Entity
@Table(name = "repair_record")
public class RepairRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bridge_id", nullable = false, length = 50)
    private String bridgeId;

    @Column(name = "repair_date", nullable = false)
    private LocalDate repairDate;

    @Column(name = "repair_type", length = 50)
    private String repairType;      // 大修/加固/日常养护/应急处置

    @Column(length = 100)
    private String component;       // 维修构件

    @Column(columnDefinition = "TEXT")
    private String description;     // 维修内容描述

    @Column(name = "repair_method", length = 200)
    private String repairMethod;    // 修复工艺

    @Column(length = 200)
    private String contractor;

    @Column(precision = 12, scale = 2)
    private BigDecimal cost;

    @Column(length = 50)
    private String result;          // 完工/持续中

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
