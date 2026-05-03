package com.bridge.agent.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户偏好实体（三级记忆架构 — 用户级）
 *
 * <p>存储用户的个性化设置，让 Agent 输出更贴合个人习惯：
 * <ul>
 *   <li>常用桥梁列表 —— 快速访问，省去每次输入桥梁编号</li>
 *   <li>惯用术语映射 —— 如用户习惯说"裂纹"，系统统一映射为规范术语"裂缝"</li>
 *   <li>偏好输出格式 —— 简洁/标准/详细，控制报告篇幅</li>
 * </ul>
 *
 * <p>面试要点：
 * "用户级记忆解决的是个性化问题。同一条裂缝，老检测员可能只需要输出关键数据，
 *  新检测员可能需要完整的规范引用。通过用户级记忆记录这个偏好，
 *  避免每次都重新告诉 Agent 用哪种风格。"
 */
@Data
@Entity
@Table(name = "user_preference")
public class UserPreference {

    @Id
    @Column(name = "user_id", length = 50)
    private String userId;

    /**
     * 常用桥梁列表 JSON，如 ["BRG-001", "BRG-003"]
     * 首页可直接展示，检测时无需手动输入编号
     */
    @Column(name = "preferred_bridges", columnDefinition = "JSON")
    private String preferredBridges;

    /**
     * 偏好输出格式：brief（简洁）/ standard（标准）/ detailed（详细）
     * 控制 Agent 生成报告的详略程度
     */
    @Column(name = "output_format", length = 50)
    private String outputFormat = "standard";

    /**
     * 用户惯用术语映射 JSON，如 {"裂纹": "裂缝", "墩子": "墩柱"}
     * 在意图分类前做同义词替换，提高识别准确率
     */
    @Column(columnDefinition = "JSON")
    private String terminology;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
