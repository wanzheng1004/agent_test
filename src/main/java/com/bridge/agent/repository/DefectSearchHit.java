package com.bridge.agent.repository;

/**
 * 稀疏检索命中结果投影
 *
 * <p>与直接返回 DefectRecord 不同，这里额外暴露 FULLTEXT score，
 * 解决旧版 sparse 路径只有排序、没有真实分数的问题。
 */
public interface DefectSearchHit {

    Long getId();

    String getBridgeId();

    String getComponent();

    String getDefectType();

    String getDescription();

    Integer getGrade();

    String getStandardRef();

    String getGradeReason();

    Double getScore();
}
