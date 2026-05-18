package com.bridge.agent.core.plan;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Mutable context for a Plan & Execute run.
 */
public class PlanExecuteContext {

    private final String runId;
    private final String originalInput;
    private ExecutionPlan originalPlan;
    private ExecutionPlan currentPlan;
    private final List<StepResult> stepResults = new CopyOnWriteArrayList<>();
    private final Map<String, String> outputKeyMap = new HashMap<>();
    private boolean replanned = false;
    private String replanReason;
    private String finalOutput;

    public PlanExecuteContext(String originalInput) {
        this(null, originalInput);
    }

    public PlanExecuteContext(String runId, String originalInput) {
        this.runId = runId;
        this.originalInput = originalInput;
    }

    public void setPlan(ExecutionPlan plan) {
        if (this.originalPlan == null) {
            this.originalPlan = plan;
        }
        this.currentPlan = plan;
    }

    public void markReplanned(String reason) {
        this.replanned = true;
        this.replanReason = reason;
    }

    public void addStepResult(StepResult result) {
        stepResults.add(result);
        if (result.isSuccess() && result.step().outputKey() != null) {
            outputKeyMap.put(result.step().outputKey(), result.result());
        }
    }

    public String resolveReference(String input) {
        if (input == null) {
            return null;
        }
        String resolved = input;
        for (Map.Entry<String, String> entry : outputKeyMap.entrySet()) {
            resolved = resolved.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return resolved;
    }

    public String toSynthesisInput() {
        return stepResults.stream()
                .map(StepResult::toSynthesisText)
                .collect(Collectors.joining("\n\n"));
    }

    public String getRunId() {
        return runId;
    }

    public String getOriginalInput() {
        return originalInput;
    }

    public ExecutionPlan getCurrentPlan() {
        return currentPlan;
    }

    public ExecutionPlan getOriginalPlan() {
        return originalPlan;
    }

    public List<StepResult> getStepResults() {
        return Collections.unmodifiableList(stepResults);
    }

    public boolean isReplanned() {
        return replanned;
    }

    public String getReplanReason() {
        return replanReason;
    }

    public String getFinalOutput() {
        return finalOutput;
    }

    public Map<String, String> getOutputKeyMap() {
        return Collections.unmodifiableMap(outputKeyMap);
    }

    public void setFinalOutput(String finalOutput) {
        this.finalOutput = finalOutput;
    }

    public String getResultByKey(String key) {
        return outputKeyMap.getOrDefault(key, "");
    }
}
