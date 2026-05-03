package com.bridge.agent.orchestrator.dto;

/**
 * 场景类型枚举
 */
public enum SceneType {
    PRE_INSPECTION,    // 检修前：汇总档案、查历史病害、生成检测建议
    DURING_INSPECTION, // 检测中：查规范、评级、生成标准描述
    POST_INSPECTION,   // 检修后：汇总病害、生成处置建议
    GENERAL            // 通用问答：规范查询、历史追溯等
}
