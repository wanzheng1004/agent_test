package com.bridge.agent.core.plan;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One tool invocation planned by the planner.
 */
public record PlanStep(
        @JsonProperty("tool") String tool,
        @JsonProperty("input") Object input,
        @JsonProperty("description") String description,
        @JsonProperty("outputKey") String outputKey
) {
}
