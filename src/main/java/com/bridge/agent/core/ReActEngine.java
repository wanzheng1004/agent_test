package com.bridge.agent.core;

import com.bridge.agent.llm.AgentLlmClient;
import com.bridge.agent.runtime.AgentEventType;
import com.bridge.agent.runtime.AgentRun;
import com.bridge.agent.runtime.AgentRuntimeRecorder;
import com.bridge.agent.advisor.GuardrailAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ReActEngine {

    private static final Logger log = LoggerFactory.getLogger(ReActEngine.class);

    private final AgentLlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutorRuntime toolRuntime;
    private final ThinkResultParser parser;
    private final AgentRuntimeRecorder runtimeRecorder;

    @Value("${bridge.agent.react.max-iterations:8}")
    private int maxIterations = 8;

    @Value("${bridge.agent.react.max-consecutive-errors:2}")
    private int maxConsecutiveErrors = 2;

    public ReActEngine(AgentLlmClient llmClient,
                       ToolRegistry toolRegistry,
                       ThinkResultParser parser,
                       AgentRuntimeRecorder runtimeRecorder) {
        this(llmClient, toolRegistry, new ToolExecutorRuntime(
                toolRegistry, runtimeRecorder, new GuardrailAdvisor()), parser, runtimeRecorder);
    }

    @Autowired
    public ReActEngine(AgentLlmClient llmClient,
                       ToolRegistry toolRegistry,
                       ToolExecutorRuntime toolRuntime,
                       ThinkResultParser parser,
                       AgentRuntimeRecorder runtimeRecorder) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.toolRuntime = toolRuntime;
        this.parser = parser;
        this.runtimeRecorder = runtimeRecorder;
    }

    public AgentContext execute(String userInput,
                                List<Message> history,
                                Set<String> allowedTools,
                                String systemPrompt,
                                String sessionId,
                                String agentName) {
        AgentRun run = runtimeRecorder.startRun(agentName, "REACT", sessionId, userInput);
        AgentContext ctx = new AgentContext(run.getRunId(), sessionId, agentName);
        String toolDescriptions = toolRegistry.renderDescriptions(allowedTools);

        log.info("[{}] ReAct started, runId={}, allowedTools={}",
                agentName, run.getRunId(), allowedTools);

        for (int i = 0; i < maxIterations && ctx.isRunning(); i++) {
            String llmOutput = think(ctx, userInput, history, toolDescriptions, systemPrompt);
            ThinkResult thought = parser.parse(llmOutput);

            if (!thought.isValid()) {
                runtimeRecorder.record(run.getRunId(), AgentEventType.LLM_COMPLETED,
                        "Invalid LLM thought, self-correction requested",
                        Map.of("stepIndex", i, "rawOutputLength", llmOutput == null ? 0 : llmOutput.length()), 0);
                thought = selfCorrect(ctx, llmOutput, thought, allowedTools, toolDescriptions, systemPrompt);

                if (!thought.isValid()) {
                    AgentStep errorStep = new AgentStep(
                            i,
                            thought.thought(),
                            "INVALID",
                            thought.actionInput(),
                            "Invalid output format after self-correction",
                            0,
                            StepStatus.THOUGHT_LOW_QUALITY);
                    ctx.addStep(errorStep);
                    recordStep(run.getRunId(), errorStep);
                    if (ctx.consecutiveErrorCount() >= maxConsecutiveErrors) {
                        terminateWithFallback(ctx, AgentState.FAILED,
                                TerminationReason.CONSECUTIVE_ERRORS, userInput);
                    }
                    continue;
                }
            }

            if ("FINISH".equals(thought.action())) {
                ctx.setFinalAnswer(thought.actionInput());
                ctx.terminate(AgentState.FINISHED, TerminationReason.NORMAL_FINISH);
                break;
            }

            if ("ASK_USER".equals(thought.action())) {
                ctx.setFinalAnswer(thought.actionInput());
                ctx.terminate(AgentState.FINISHED, TerminationReason.WAITING_USER_INPUT);
                break;
            }

            AgentStep step = executeToolStep(i, thought, allowedTools, ctx, run.getRunId());
            ctx.addStep(step);
            recordStep(run.getRunId(), step);

            if (step.status() == StepStatus.PENDING_APPROVAL) {
                ctx.setFinalAnswer(step.observation());
                ctx.terminate(AgentState.WAITING_APPROVAL, TerminationReason.HUMAN_APPROVAL_REQUIRED);
                break;
            }

            if (ctx.consecutiveErrorCount() >= maxConsecutiveErrors) {
                terminateWithFallback(ctx, AgentState.FAILED,
                        TerminationReason.CONSECUTIVE_ERRORS, userInput);
            }
        }

        if (ctx.isRunning()) {
            terminateWithFallback(ctx, AgentState.MAX_ITER_EXCEEDED,
                    TerminationReason.MAX_ITERATIONS, userInput);
        }

        runtimeRecorder.finishRun(run.getRunId(), ctx.getState(),
                ctx.getTerminationReason(), ctx.getFinalAnswer());
        log.info("[{}] ReAct completed, runId={}, state={}, steps={}",
                agentName, run.getRunId(), ctx.getState(), ctx.getSteps().size());
        return ctx;
    }

    private AgentStep executeToolStep(int index,
                                      ThinkResult thought,
                                      Set<String> allowedTools,
                                      AgentContext ctx,
                                      String runId) {
        long start = System.currentTimeMillis();
        String observation;
        StepStatus status;
        boolean recordedByToolRuntime = false;

        try {
            if (allowedTools != null && !allowedTools.contains(thought.action())) {
                throw new InvalidActionException("Tool '" + thought.action()
                        + "' is not allowed for this agent. Allowed tools: "
                        + String.join(", ", allowedTools));
            }
            ToolExecutionResult result = toolRuntime.execute(new ToolExecutionRequest(
                    runId,
                    thought.action(),
                    thought.actionInput(),
                    ctx.getAgentName(),
                    index));
            recordedByToolRuntime = true;
            observation = result.output();
            status = result.status();
        } catch (InvalidActionException e) {
            observation = e.getMessage();
            status = StepStatus.INVALID_ACTION;
        } catch (Exception e) {
            observation = "Tool execution failed: " + e.getMessage();
            status = StepStatus.TOOL_ERROR;
        }

        long latency = System.currentTimeMillis() - start;
        if (!recordedByToolRuntime && (status == StepStatus.INVALID_ACTION || status == StepStatus.TOOL_ERROR)) {
            runtimeRecorder.record(runId, AgentEventType.TOOL_FAILED,
                    observation,
                    Map.of("tool", thought.action(), "status", status.name(), "stepIndex", index),
                    latency);
        }

        return new AgentStep(index, thought.thought(), thought.action(),
                thought.actionInput(), observation, latency, status);
    }

    private String think(AgentContext ctx,
                         String userInput,
                         List<Message> history,
                         String toolDescriptions,
                         String systemPrompt) {
        String renderedSystemPrompt = renderPrompt(systemPrompt, toolDescriptions, ctx.getTrajectoryText(), userInput);
        String userMessage = buildUserMessage(ctx.getTrajectoryText(), userInput);
        long start = System.currentTimeMillis();
        runtimeRecorder.record(ctx.getRunId(), AgentEventType.LLM_STARTED,
                "LLM think started", Map.of("agentName", ctx.getAgentName()), 0);
        String output = llmClient.complete(renderedSystemPrompt, history, userMessage, Map.of(
                "agentName", ctx.getAgentName(),
                "sessionId", ctx.getSessionId()
        ));
        runtimeRecorder.record(ctx.getRunId(), AgentEventType.LLM_COMPLETED,
                "LLM think completed",
                Map.of("outputLength", output == null ? 0 : output.length()),
                System.currentTimeMillis() - start);
        return output;
    }

    private ThinkResult selfCorrect(AgentContext ctx,
                                    String badOutput,
                                    ThinkResult badResult,
                                    Set<String> allowedTools,
                                    String toolDescriptions,
                                    String systemPrompt) {
        String problemDesc = parser.diagnoseProblem(badResult, badOutput);
        String correctionPrompt = """
                The previous output did not follow the required ReAct format.
                Problem: %s

                Return exactly:
                Thought: <brief reasoning>
                Action: <tool name, FINISH, or ASK_USER>
                ActionInput: <tool JSON input or final answer>

                Available tools:
                %s
                """.formatted(problemDesc, toolDescriptions);

        String corrected = llmClient.complete(correctionPrompt, List.of(),
                "Trajectory:\n" + ctx.getTrajectoryText(),
                Map.of("agentName", ctx.getAgentName(), "sessionId", ctx.getSessionId()));
        return parser.parse(corrected);
    }

    private void terminateWithFallback(AgentContext ctx,
                                       AgentState state,
                                       TerminationReason reason,
                                       String userInput) {
        String collectedInfo = ctx.getSteps().stream()
                .filter(s -> s.status() == StepStatus.SUCCESS)
                .map(s -> "[" + s.action() + "]\n" + s.observation())
                .reduce("", (a, b) -> a + "\n\n" + b);

        String fallback;
        if (collectedInfo.isBlank()) {
            fallback = "The agent could not complete the request. Please add more detail or try again.";
        } else {
            fallback = llmClient.complete(
                    "Answer from the partial observations. Be explicit about missing data.",
                    List.of(),
                    "User request:\n" + userInput + "\n\nCollected observations:\n" + collectedInfo,
                    Map.of("agentName", ctx.getAgentName(), "sessionId", ctx.getSessionId()));
        }

        ctx.setFinalAnswer(fallback);
        ctx.terminate(state, reason);
    }

    private void recordStep(String runId, AgentStep step) {
        runtimeRecorder.record(runId, AgentEventType.STEP_RECORDED,
                "ReAct step recorded",
                Map.of(
                        "stepIndex", step.stepIndex(),
                        "action", step.action(),
                        "status", step.status().name()
                ),
                step.latencyMs());
    }

    private String renderPrompt(String systemPrompt,
                                String toolDescriptions,
                                String trajectory,
                                String userInput) {
        return (systemPrompt == null ? "" : systemPrompt)
                .replace("{toolDescriptions}", toolDescriptions == null ? "" : toolDescriptions)
                .replace("{trajectory}", trajectory == null ? "" : trajectory)
                .replace("{userInput}", userInput == null ? "" : userInput);
    }

    private String buildUserMessage(String trajectory, String userInput) {
        if (trajectory == null || trajectory.contains("(no previous steps)")) {
            return "User request:\n" + userInput + "\n\nStart reasoning.";
        }
        return "User request:\n" + userInput
                + "\n\nPrevious trajectory:\n" + trajectory
                + "\nContinue with the next step.";
    }
}
