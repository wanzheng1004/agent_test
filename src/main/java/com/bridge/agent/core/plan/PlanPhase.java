package com.bridge.agent.core.plan;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One phase in a Plan & Execute workflow.
 */
public record PlanPhase(
        @JsonProperty("name") String name,
        @JsonProperty("parallel") boolean parallel,
        @JsonProperty("steps") List<PlanStep> steps,
        @JsonProperty("postCondition") String postCondition
) {
    public List<PlanStep> steps() {
        return steps == null ? List.of() : steps;
    }

    public boolean hasPostCondition() {
        return postCondition != null && !postCondition.isBlank();
    }
}
