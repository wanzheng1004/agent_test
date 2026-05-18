package com.bridge.agent.core.plan;

import com.bridge.agent.core.AgentState;
import com.bridge.agent.core.InvalidActionException;
import com.bridge.agent.core.StepStatus;
import com.bridge.agent.core.TerminationReason;
import com.bridge.agent.core.ToolExecutionRequest;
import com.bridge.agent.core.ToolExecutionResult;
import com.bridge.agent.core.ToolExecutorRuntime;
import com.bridge.agent.core.ToolRegistry;
import com.bridge.agent.advisor.GuardrailAdvisor;
import com.bridge.agent.llm.AgentLlmClient;
import com.bridge.agent.runtime.AgentEventType;
import com.bridge.agent.runtime.AgentRun;
import com.bridge.agent.runtime.AgentRuntimeRecorder;
import com.bridge.agent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Component
public class PlanExecuteEngine {

    private static final Logger log = LoggerFactory.getLogger(PlanExecuteEngine.class);
    private static final int MAX_REPLAN = 1;

    private final AgentLlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutorRuntime toolRuntime;
    private final AgentRuntimeRecorder runtimeRecorder;

    public PlanExecuteEngine(AgentLlmClient llmClient,
                             ToolRegistry toolRegistry,
                             AgentRuntimeRecorder runtimeRecorder) {
        this(llmClient, toolRegistry,
                new ToolExecutorRuntime(toolRegistry, runtimeRecorder, new GuardrailAdvisor()),
                runtimeRecorder);
    }

    @Autowired
    public PlanExecuteEngine(AgentLlmClient llmClient,
                             ToolRegistry toolRegistry,
                             ToolExecutorRuntime toolRuntime,
                             AgentRuntimeRecorder runtimeRecorder) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.toolRuntime = toolRuntime;
        this.runtimeRecorder = runtimeRecorder;
    }

    public PlanExecuteContext execute(String userInput,
                                      String plannerPrompt,
                                      String synthesizerPrompt,
                                      Set<String> allowedTools,
                                      String agentName) {
        AgentRun run = runtimeRecorder.startRun(agentName, "PLAN_EXECUTE", null, userInput);
        PlanExecuteContext ctx = new PlanExecuteContext(run.getRunId(), userInput);
        int replanCount = 0;

        log.info("[{}] Plan&Execute started, runId={}", agentName, run.getRunId());

        ExecutionPlan plan = generatePlan(userInput, plannerPrompt, allowedTools, null, run.getRunId());
        ctx.setPlan(plan);

        List<String> invalidTools = validatePlan(plan, allowedTools);
        if (!invalidTools.isEmpty()) {
            String errorMsg = "Plan contains invalid tools: " + invalidTools;
            runtimeRecorder.record(run.getRunId(), AgentEventType.REPLAN_TRIGGERED,
                    errorMsg, Map.of("invalidTools", invalidTools), 0);
            plan = generatePlan(userInput, plannerPrompt, allowedTools, errorMsg, run.getRunId());
            ctx.setPlan(plan);
            ctx.markReplanned(errorMsg);
            replanCount++;
        }

        for (PlanPhase phase : plan.phases()) {
            log.info("[{}] Executing phase: {}, parallel={}", agentName, phase.name(), phase.parallel());
            if (phase.parallel()) {
                executeParallelPhase(phase, ctx);
            } else {
                executeSequentialPhase(phase, ctx);
            }

            if (hasPendingApproval(ctx)) {
                ctx.setFinalOutput("Execution is waiting for human approval.");
                runtimeRecorder.finishRun(run.getRunId(), AgentState.WAITING_APPROVAL,
                        TerminationReason.HUMAN_APPROVAL_REQUIRED, ctx.getFinalOutput());
                return ctx;
            }

            if (phase.hasPostCondition() && replanCount < MAX_REPLAN) {
                String conditionResult = evaluatePostCondition(phase.postCondition(), ctx);
                if (conditionResult != null) {
                    runtimeRecorder.record(run.getRunId(), AgentEventType.REPLAN_TRIGGERED,
                            conditionResult, Map.of("phase", phase.name()), 0);
                    plan = generatePlan(userInput, plannerPrompt, allowedTools, conditionResult, run.getRunId());
                    ctx.setPlan(plan);
                    ctx.markReplanned(conditionResult);
                    replanCount++;
                    executePlanWithoutFurtherReplan(plan, ctx, agentName);
                    break;
                }
            }
        }

        String finalOutput = synthesize(ctx, synthesizerPrompt, userInput, agentName);
        ctx.setFinalOutput(finalOutput);
        runtimeRecorder.finishRun(run.getRunId(), AgentState.FINISHED,
                TerminationReason.NORMAL_FINISH, finalOutput);

        log.info("[{}] Plan&Execute completed, runId={}, steps={}, replanned={}",
                agentName, run.getRunId(), ctx.getStepResults().size(), ctx.isReplanned());
        return ctx;
    }

    private void executePlanWithoutFurtherReplan(ExecutionPlan plan,
                                                 PlanExecuteContext ctx,
                                                 String agentName) {
        for (PlanPhase phase : plan.phases()) {
            log.info("[{}] Executing replanned phase: {}, parallel={}",
                    agentName, phase.name(), phase.parallel());
            if (phase.parallel()) {
                executeParallelPhase(phase, ctx);
            } else {
                executeSequentialPhase(phase, ctx);
            }
            if (hasPendingApproval(ctx)) {
                return;
            }
        }
    }

    private ExecutionPlan generatePlan(String userInput,
                                       String plannerPrompt,
                                       Set<String> allowedTools,
                                       String replanReason,
                                       String runId) {
        String toolDescriptions = toolRegistry.renderDescriptions(allowedTools);
        String prompt = (plannerPrompt == null ? "" : plannerPrompt)
                .replace("{toolDescriptions}", toolDescriptions);

        String userMsg = replanReason == null
                ? "Generate an execution plan for:\n" + userInput
                : "The previous plan failed because: " + replanReason
                + "\nGenerate a corrected execution plan for:\n" + userInput;

        long start = System.currentTimeMillis();
        runtimeRecorder.record(runId, AgentEventType.LLM_STARTED,
                "Planner LLM started", Map.of(), 0);
        String response = llmClient.complete(prompt, List.of(), userMsg,
                Map.of("agentName", "PlanExecuteEngine/Planner"));
        runtimeRecorder.record(runId, AgentEventType.LLM_COMPLETED,
                "Planner LLM completed",
                Map.of("outputLength", response == null ? 0 : response.length()),
                System.currentTimeMillis() - start);

        String json = extractJson(response);
        return JsonUtil.parse(json, ExecutionPlan.class);
    }

    private List<String> validatePlan(ExecutionPlan plan, Set<String> allowedTools) {
        return plan.phases().stream()
                .flatMap(phase -> phase.steps().stream())
                .map(PlanStep::tool)
                .filter(tool -> tool == null
                        || (allowedTools != null && !allowedTools.contains(tool))
                        || !toolRegistry.exists(tool))
                .collect(Collectors.toList());
    }

    private void executeParallelPhase(PlanPhase phase, PlanExecuteContext ctx) {
        List<CompletableFuture<StepResult>> futures = phase.steps().stream()
                .map(step -> CompletableFuture.supplyAsync(() -> executeSingleStep(step, ctx)))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        futures.forEach(f -> ctx.addStepResult(f.join()));
    }

    private void executeSequentialPhase(PlanPhase phase, PlanExecuteContext ctx) {
        for (PlanStep step : phase.steps()) {
            ctx.addStepResult(executeSingleStep(step, ctx));
        }
    }

    private StepResult executeSingleStep(PlanStep step, PlanExecuteContext ctx) {
        long start = System.currentTimeMillis();
        try {
            if (!toolRegistry.exists(step.tool())) {
                throw new InvalidActionException("Unknown tool: " + step.tool());
            }
            String rawInput = JsonUtil.toJson(step.input());
            String resolvedInput = ctx.resolveReference(rawInput);
            ToolExecutionResult result = toolRuntime.execute(new ToolExecutionRequest(
                    ctx.getRunId(),
                    step.tool(),
                    resolvedInput,
                    "PLAN_EXECUTE",
                    ctx.getStepResults().size()));
            if (result.status() == StepStatus.PENDING_APPROVAL) {
                return new StepResult(step, result.output(), result.status(), result.latencyMs());
            }
            if (!result.success()) {
                return new StepResult(step, result.output(), result.status(), result.latencyMs());
            }
            return new StepResult(step, result.output(), StepStatus.SUCCESS, result.latencyMs());
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            runtimeRecorder.record(ctx.getRunId(), AgentEventType.TOOL_FAILED,
                    e.getMessage(), Map.of("tool", nullToEmpty(step.tool())), latency);
            return new StepResult(step, "Execution failed: " + e.getMessage(),
                    StepStatus.TOOL_ERROR, latency);
        }
    }

    private String synthesize(PlanExecuteContext ctx,
                              String synthesizerPrompt,
                              String userInput,
                              String agentName) {
        boolean hasFailedSteps = ctx.getStepResults().stream().anyMatch(r -> !r.isSuccess());
        String userMsg = """
                User request:
                %s

                Execution results:
                %s

                %s
                Generate the final answer from the available data.
                """.formatted(
                userInput,
                ctx.toSynthesisInput(),
                hasFailedSteps ? "Some steps failed; disclose missing data." : "");

        long start = System.currentTimeMillis();
        runtimeRecorder.record(ctx.getRunId(), AgentEventType.LLM_STARTED,
                "Synthesizer LLM started", Map.of(), 0);
        String output = llmClient.complete(synthesizerPrompt, List.of(), userMsg,
                Map.of("agentName", agentName + "/Synthesizer"));
        runtimeRecorder.record(ctx.getRunId(), AgentEventType.LLM_COMPLETED,
                "Synthesizer LLM completed",
                Map.of("outputLength", output == null ? 0 : output.length()),
                System.currentTimeMillis() - start);
        return output;
    }

    private String evaluatePostCondition(String condition, PlanExecuteContext ctx) {
        long failCount = ctx.getStepResults().stream().filter(r -> !r.isSuccess()).count();
        if (failCount == ctx.getStepResults().size() && !ctx.getStepResults().isEmpty()) {
            return "All steps failed; cannot satisfy post-condition: " + condition;
        }
        return null;
    }

    private String extractJson(String response) {
        if (response == null) {
            return "{\"phases\":[]}";
        }
        String cleaned = response.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean hasPendingApproval(PlanExecuteContext ctx) {
        return ctx.getStepResults().stream()
                .anyMatch(result -> result.status() == StepStatus.PENDING_APPROVAL);
    }
}
