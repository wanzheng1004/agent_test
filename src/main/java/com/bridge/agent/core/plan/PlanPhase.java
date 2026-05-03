package com.bridge.agent.core.plan;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Plan & Execute 中的执行阶段
 *
 * <p>一个 Plan 由多个 Phase 组成，Phase 内的步骤可以并行或串行执行。
 *
 * @param name          阶段名称（用于日志和报告）
 * @param parallel      本阶段步骤是否可以并行执行
 * @param steps         本阶段的执行步骤列表
 * @param postCondition 阶段完成后的检查条件（可选），不满足时触发 Replan
 */
public record PlanPhase(
        @JsonProperty("name") String name,
        @JsonProperty("parallel") boolean parallel,
        @JsonProperty("steps") List<PlanStep> steps,
        @JsonProperty("postCondition") String postCondition
) {
    public boolean hasPostCondition() {
        return postCondition != null && !postCondition.isBlank();
    }
}
