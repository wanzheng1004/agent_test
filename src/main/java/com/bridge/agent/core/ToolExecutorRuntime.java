package com.bridge.agent.core;

import com.bridge.agent.advisor.GuardrailAdvisor;
import com.bridge.agent.advisor.GuardrailDecision;
import com.bridge.agent.runtime.AgentEventType;
import com.bridge.agent.runtime.AgentRuntimeRecorder;
import com.bridge.agent.util.JsonUtil;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class ToolExecutorRuntime {

    private final ToolRegistry registry;
    private final AgentRuntimeRecorder runtimeRecorder;
    private final GuardrailAdvisor guardrailAdvisor;
    private final Map<String, ToolExecutionRequest> pendingApprovals = new ConcurrentHashMap<>();

    public ToolExecutorRuntime(ToolRegistry registry,
                               AgentRuntimeRecorder runtimeRecorder,
                               GuardrailAdvisor guardrailAdvisor) {
        this.registry = registry;
        this.runtimeRecorder = runtimeRecorder;
        this.guardrailAdvisor = guardrailAdvisor;
    }

    public ToolExecutionResult execute(ToolExecutionRequest request) {
        long start = System.currentTimeMillis();
        ToolSpec spec = registry.getSpec(request.toolName());
        if (spec == null) {
            return failed(request, start, StepStatus.INVALID_ACTION,
                    "Unknown tool: " + request.toolName());
        }
        if (!isAllowedInScene(spec, request.scene())) {
            return failed(request, start, StepStatus.INVALID_ACTION,
                    "Tool '" + request.toolName() + "' is not allowed in scene " + request.scene());
        }
        if (!looksLikeJsonObject(spec.inputSchema(), request.inputJson())) {
            return failed(request, start, StepStatus.INVALID_ACTION,
                    "Tool input must be a JSON object.");
        }

        GuardrailDecision decision = guardrailAdvisor.checkTool(spec, request.inputJson());
        if (!decision.allowed() || decision.requiresHumanReview() || spec.requiresApproval()) {
            String approvalId = UUID.randomUUID().toString();
            pendingApprovals.put(approvalId, request);
            runtimeRecorder.record(request.runId(), AgentEventType.APPROVAL_REQUIRED,
                    "Tool call is waiting for human approval", Map.of(
                            "approvalId", approvalId,
                            "tool", request.toolName(),
                            "warnings", decision.warnings()
                    ), 0);
            runtimeRecorder.saveCheckpoint(request.runId(), "TOOL_APPROVAL_REQUIRED",
                    AgentState.WAITING_APPROVAL,
                    TerminationReason.HUMAN_APPROVAL_REQUIRED,
                    Map.of(
                            "approvalId", approvalId,
                            "tool", request.toolName(),
                            "inputJson", request.inputJson(),
                            "scene", request.scene() == null ? "" : request.scene()
                    ));
            return new ToolExecutionResult(request.toolName(),
                    "PENDING_APPROVAL:" + approvalId,
                    StepStatus.PENDING_APPROVAL,
                    System.currentTimeMillis() - start,
                    approvalId);
        }

        return executeApproved(request, start);
    }

    public ToolExecutionResult resumeApproval(String runId, ResumeDecision decision) {
        String approvalId = findLatestApprovalId(runId);
        if (approvalId == null) {
            runtimeRecorder.record(runId, AgentEventType.APPROVAL_RESOLVED,
                    "No pending approval was found", Map.of(
                            "action", decision.action() == null ? "" : decision.action()), 0);
            return new ToolExecutionResult("", "No pending approval was found.",
                    StepStatus.INVALID_ACTION, 0, null);
        }

        if (!decision.approve() && !decision.edit() && !decision.reject()) {
            runtimeRecorder.record(runId, AgentEventType.APPROVAL_RESOLVED,
                    "Invalid approval action", Map.of(
                            "approvalId", approvalId,
                            "action", decision.action() == null ? "" : decision.action()
                    ), 0);
            return new ToolExecutionResult("", "Invalid approval action: " + decision.action(),
                    StepStatus.INVALID_ACTION, 0, approvalId);
        }

        ToolExecutionRequest original = pendingApprovals.remove(approvalId);
        runtimeRecorder.record(runId, AgentEventType.APPROVAL_RESOLVED,
                "Human approval decision received", Map.of(
                        "approvalId", approvalId,
                        "action", decision.action(),
                        "userId", decision.userId() == null ? "" : decision.userId()
                ), 0);

        if (decision.reject()) {
            return new ToolExecutionResult(original.toolName(),
                    "Human rejected tool execution: " + Objects.toString(decision.comment(), ""),
                    StepStatus.INVALID_ACTION,
                    0,
                    approvalId);
        }

        String input = decision.edit() && decision.editedInputJson() != null
                ? decision.editedInputJson()
                : original.inputJson();
        ToolExecutionRequest approvedRequest = new ToolExecutionRequest(
                original.runId(),
                original.toolName(),
                input,
                original.scene(),
                original.stepIndex());
        ToolExecutionResult result = executeApproved(approvedRequest, System.currentTimeMillis());
        runtimeRecorder.saveCheckpoint(runId, "TOOL_APPROVAL_RESOLVED",
                result.success() ? AgentState.RUNNING : AgentState.FAILED,
                result.success() ? null : TerminationReason.TOOL_FAILURE,
                Map.of(
                        "approvalId", approvalId,
                        "tool", original.toolName(),
                        "status", result.status().name()
                ));
        return result;
    }

    private ToolExecutionResult executeApproved(ToolExecutionRequest request, long start) {
        ToolRegistry.ToolDefinition definition = registry.getTools().get(request.toolName());
        ToolSpec spec = definition.spec();
        runtimeRecorder.record(request.runId(), AgentEventType.TOOL_STARTED,
                "Tool call started", Map.of("tool", request.toolName(), "stepIndex", request.stepIndex()), 0);
        try {
            String output = callWithTimeout(definition, request.inputJson(), spec.timeout());
            long latency = System.currentTimeMillis() - start;
            runtimeRecorder.record(request.runId(), AgentEventType.TOOL_COMPLETED,
                    "Tool call completed", Map.of(
                            "tool", request.toolName(),
                            "outputLength", output == null ? 0 : output.length()
                    ), latency);
            return new ToolExecutionResult(request.toolName(), output, StepStatus.SUCCESS, latency, null);
        } catch (Exception e) {
            return failed(request, start, StepStatus.TOOL_ERROR, "Tool execution failed: " + e.getMessage());
        }
    }

    private String callWithTimeout(ToolRegistry.ToolDefinition definition,
                                   String inputJson,
                                   Duration timeout) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> definition.executor().execute(inputJson));
            long timeoutMs = timeout == null ? 20_000 : Math.max(1, timeout.toMillis());
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new TimeoutException("Timed out after " + (timeout == null ? 20_000 : timeout.toMillis()) + "ms");
        } finally {
            executor.shutdownNow();
        }
    }

    private ToolExecutionResult failed(ToolExecutionRequest request,
                                       long start,
                                       StepStatus status,
                                       String message) {
        long latency = System.currentTimeMillis() - start;
        runtimeRecorder.record(request.runId(), AgentEventType.TOOL_FAILED,
                message, Map.of(
                        "tool", request.toolName() == null ? "" : request.toolName(),
                        "status", status.name()
                ), latency);
        return new ToolExecutionResult(request.toolName(), message, status, latency, null);
    }

    private boolean isAllowedInScene(ToolSpec spec, String scene) {
        return spec.allowedScenes().isEmpty()
                || scene == null
                || spec.allowedScenes().contains(scene);
    }

    private boolean looksLikeJsonObject(String schema, String inputJson) {
        if (schema == null || schema.isBlank() || !schema.trim().startsWith("{")) {
            return true;
        }
        if (inputJson == null || !inputJson.trim().startsWith("{")) {
            return false;
        }
        try {
            return JsonUtil.toTree(inputJson).isObject();
        } catch (Exception e) {
            return false;
        }
    }

    private String findLatestApprovalId(String runId) {
        return runtimeRecorder.latestCheckpoint(runId)
                .map(checkpoint -> checkpoint.payload().get("approvalId"))
                .map(Object::toString)
                .filter(pendingApprovals::containsKey)
                .orElseGet(() -> pendingApprovals.entrySet().stream()
                        .filter(entry -> entry.getValue().runId().equals(runId))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElse(null));
    }
}
