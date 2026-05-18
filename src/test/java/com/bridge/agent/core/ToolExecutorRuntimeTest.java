package com.bridge.agent.core;

import com.bridge.agent.advisor.GuardrailAdvisor;
import com.bridge.agent.runtime.AgentRuntimeRecorder;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutorRuntimeTest {

    @Test
    void pausesWriteToolForApprovalAndExecutesAfterApprove() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(ToolSpec.write("save", "Save", "{}"), input -> "saved:" + input);
        AgentRuntimeRecorder recorder = new AgentRuntimeRecorder();
        String runId = recorder.startRun("agent", "DURING_INSPECTION", "s1", "input").getRunId();
        ToolExecutorRuntime runtime = new ToolExecutorRuntime(registry, recorder, new GuardrailAdvisor());

        ToolExecutionResult pending = runtime.execute(new ToolExecutionRequest(
                runId, "save", "{\"x\":1}", "DURING_INSPECTION", 0));

        assertThat(pending.status()).isEqualTo(StepStatus.PENDING_APPROVAL);
        assertThat(recorder.getCheckpoints(runId)).anyMatch(c -> "TOOL_APPROVAL_REQUIRED".equals(c.stage()));

        ToolExecutionResult approved = runtime.resumeApproval(runId,
                new ResumeDecision("approve", null, "ok", "u1"));

        assertThat(approved.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(approved.output()).isEqualTo("saved:{\"x\":1}");
    }

    @Test
    void enforcesToolTimeout() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(ToolSpec.readOnly("slow", "Slow", "{}")
                .withTimeout(Duration.ofMillis(20)), input -> {
            Thread.sleep(200);
            return "late";
        });
        AgentRuntimeRecorder recorder = new AgentRuntimeRecorder();
        String runId = recorder.startRun("agent", "GENERAL", "s1", "input").getRunId();
        ToolExecutorRuntime runtime = new ToolExecutorRuntime(registry, recorder, new GuardrailAdvisor());

        ToolExecutionResult result = runtime.execute(new ToolExecutionRequest(
                runId, "slow", "{}", "GENERAL", 0));

        assertThat(result.status()).isEqualTo(StepStatus.TOOL_ERROR);
        assertThat(result.output()).contains("Timed out");
    }

    @Test
    void rejectsInvalidApprovalActionWithoutConsumingPendingRequest() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(ToolSpec.write("save", "Save", "{}"), input -> "saved");
        AgentRuntimeRecorder recorder = new AgentRuntimeRecorder();
        String runId = recorder.startRun("agent", "DURING_INSPECTION", "s1", "input").getRunId();
        ToolExecutorRuntime runtime = new ToolExecutorRuntime(registry, recorder, new GuardrailAdvisor());

        runtime.execute(new ToolExecutionRequest(runId, "save", "{}", "DURING_INSPECTION", 0));

        ToolExecutionResult invalid = runtime.resumeApproval(runId,
                new ResumeDecision("maybe", null, "", "u1"));
        ToolExecutionResult approved = runtime.resumeApproval(runId,
                new ResumeDecision("approve", null, "", "u1"));

        assertThat(invalid.status()).isEqualTo(StepStatus.INVALID_ACTION);
        assertThat(approved.status()).isEqualTo(StepStatus.SUCCESS);
    }

    @Test
    void rejectsSceneOutsideAllowList() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(ToolSpec.readOnly("lookup", "Lookup", "{}")
                .withAllowedScenes("PRE_INSPECTION"), input -> "ok");
        AgentRuntimeRecorder recorder = new AgentRuntimeRecorder();
        String runId = recorder.startRun("agent", "GENERAL", "s1", "input").getRunId();
        ToolExecutorRuntime runtime = new ToolExecutorRuntime(registry, recorder, new GuardrailAdvisor());

        ToolExecutionResult result = runtime.execute(new ToolExecutionRequest(
                runId, "lookup", "{}", "GENERAL", 0));

        assertThat(result.status()).isEqualTo(StepStatus.INVALID_ACTION);
        assertThat(result.output()).contains("not allowed");
    }
}
