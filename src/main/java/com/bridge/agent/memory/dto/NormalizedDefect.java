package com.bridge.agent.memory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 规范化病害记录 DTO（存储于 Redis 会话记忆中）
 *
 * <p>在检测中 Agent 每完成一次 normalize_defect 工具调用，
 * 就将结果作为 NormalizedDefect 写入 Redis，
 * 供检修后 Agent 汇总使用。
 */
public class NormalizedDefect implements Serializable {

    @JsonProperty("component")
    private String component;         // 构件，如 "0#桥墩墩柱"

    @JsonProperty("defectType")
    private String defectType;        // 病害类型

    @JsonProperty("description")
    private String description;       // 规范化描述

    @JsonProperty("grade")
    private Integer grade;            // 病害等级 1-4

    @JsonProperty("standardRef")
    private String standardRef;       // 规范条款

    @JsonProperty("gradeReason")
    private String gradeReason;       // 定级依据

    @JsonProperty("urgency")
    private String urgency;           // 处置紧迫度

    @JsonProperty("inspectionDate")
    private LocalDate inspectionDate; // 记录日期

    @JsonProperty("rawDescription")
    private String rawDescription;    // 原始输入（存档）

    // 构造器
    public NormalizedDefect() {}

    public NormalizedDefect(String component, String defectType, String description,
                             Integer grade, String standardRef, String gradeReason,
                             String urgency) {
        this.component = component;
        this.defectType = defectType;
        this.description = description;
        this.grade = grade;
        this.standardRef = standardRef;
        this.gradeReason = gradeReason;
        this.urgency = urgency;
        this.inspectionDate = LocalDate.now();
    }

    // Getters / Setters
    public String getComponent()               { return component; }
    public String getDefectType()              { return defectType; }
    public String getDescription()             { return description; }
    public Integer getGrade()                  { return grade; }
    public String getStandardRef()             { return standardRef; }
    public String getGradeReason()             { return gradeReason; }
    public String getUrgency()                 { return urgency; }
    public LocalDate getInspectionDate()       { return inspectionDate; }
    public String getRawDescription()          { return rawDescription; }

    public void setComponent(String v)         { this.component = v; }
    public void setDefectType(String v)        { this.defectType = v; }
    public void setDescription(String v)       { this.description = v; }
    public void setGrade(Integer v)            { this.grade = v; }
    public void setStandardRef(String v)       { this.standardRef = v; }
    public void setGradeReason(String v)       { this.gradeReason = v; }
    public void setUrgency(String v)           { this.urgency = v; }
    public void setInspectionDate(LocalDate v) { this.inspectionDate = v; }
    public void setRawDescription(String v)    { this.rawDescription = v; }
}
