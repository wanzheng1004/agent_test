package com.bridge.agent.core.plan;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Plan & Execute 的完整执行计划
 *
 * <p>由 LLM 在 PLAN 阶段生成，格式为 JSON。
 * 包含按顺序执行的多个 Phase，每个 Phase 可以并行或串行。
 *
 * <p>LLM 生成示例：
 * <pre>
 * {
 *   "phases": [
 *     {
 *       "name": "数据采集阶段",
 *       "parallel": true,
 *       "steps": [
 *         {"tool": "query_bridge_profile", "input": {"bridgeId": "BRG-001"}, ...},
 *         {"tool": "search_defect_history", "input": {"bridgeId": "BRG-001", "months": 36}, ...}
 *       ]
 *     },
 *     {
 *       "name": "聚类分析阶段",
 *       "parallel": false,
 *       "steps": [
 *         {"tool": "cluster_defects", "input": {"data": "${step_1b}"}, ...}
 *       ]
 *     }
 *   ]
 * }
 * </pre>
 */
public record ExecutionPlan(
        @JsonProperty("phases") List<PlanPhase> phases
) {}
