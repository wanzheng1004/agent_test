package com.bridge.agent.core.plan;

import com.bridge.agent.core.ToolRegistry;
import com.bridge.agent.llm.AgentLlmClient;
import com.bridge.agent.runtime.AgentRuntimeRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlanExecuteEngineTest {

    @Test
    void executesSequentialPlanAndSynthesizes() {
        PlanExecuteEngine engine = engineWithResponses(validPlan(false), "final report");

        PlanExecuteContext ctx = engine.execute("inspect", "planner {toolDescriptions}",
                "synth", Set.of("echo"), "planner-agent");

        assertThat(ctx.getStepResults()).hasSize(1);
        assertThat(ctx.getStepResults().get(0).isSuccess()).isTrue();
        assertThat(ctx.getFinalOutput()).isEqualTo("final report");
    }

    @Test
    void executesParallelPlan() {
        PlanExecuteEngine engine = engineWithResponses("""
                {"phases":[{"name":"collect","parallel":true,"steps":[
                  {"tool":"echo","input":{"id":"a"},"description":"a","outputKey":"a"},
                  {"tool":"echo","input":{"id":"b"},"description":"b","outputKey":"b"}
                ]}]}
                """, "parallel report");

        PlanExecuteContext ctx = engine.execute("inspect", "planner {toolDescriptions}",
                "synth", Set.of("echo"), "planner-agent");

        assertThat(ctx.getStepResults()).hasSize(2);
        assertThat(ctx.getOutputKeyMap()).containsKeys("a", "b");
    }

    @Test
    void replansInvalidToolOnce() {
        PlanExecuteEngine engine = engineWithResponses(
                """
                        {"phases":[{"name":"bad","parallel":false,"steps":[
                          {"tool":"missing","input":{},"description":"bad","outputKey":"bad"}
                        ]}]}
                        """,
                validPlan(false),
                "replanned report");

        PlanExecuteContext ctx = engine.execute("inspect", "planner {toolDescriptions}",
                "synth", Set.of("echo"), "planner-agent");

        assertThat(ctx.isReplanned()).isTrue();
        assertThat(ctx.getFinalOutput()).isEqualTo("replanned report");
    }

    @Test
    void summarizesFailedStep() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("fail", "Fails", "{}", input -> {
            throw new IllegalStateException("boom");
        });
        PlanExecuteEngine engine = new PlanExecuteEngine(
                new QueueLlm("""
                        {"phases":[{"name":"fail","parallel":false,"steps":[
                          {"tool":"fail","input":{},"description":"fail","outputKey":"f"}
                        ]}]}
                        """, "partial report"),
                registry,
                new AgentRuntimeRecorder());

        PlanExecuteContext ctx = engine.execute("inspect", "planner {toolDescriptions}",
                "synth", Set.of("fail"), "planner-agent");

        assertThat(ctx.getStepResults()).hasSize(1);
        assertThat(ctx.getStepResults().get(0).isSuccess()).isFalse();
        assertThat(ctx.getFinalOutput()).isEqualTo("partial report");
    }

    @Test
    void executesReplannedPlanAfterPostConditionFailure() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("fail", "Fails", "{}", input -> {
            throw new IllegalStateException("boom");
        });
        registry.register("echo", "Echo", "{}", input -> "echo:" + input);
        PlanExecuteEngine engine = new PlanExecuteEngine(
                new QueueLlm("""
                        {"phases":[{"name":"fail","parallel":false,"postCondition":"must collect data","steps":[
                          {"tool":"fail","input":{},"description":"fail","outputKey":"f"}
                        ]}]}
                        """,
                        validPlan(false),
                        "replanned after postcondition"),
                registry,
                new AgentRuntimeRecorder());

        PlanExecuteContext ctx = engine.execute("inspect", "planner {toolDescriptions}",
                "synth", Set.of("fail", "echo"), "planner-agent");

        assertThat(ctx.isReplanned()).isTrue();
        assertThat(ctx.getStepResults()).hasSize(2);
        assertThat(ctx.getOutputKeyMap()).containsKey("a");
        assertThat(ctx.getFinalOutput()).isEqualTo("replanned after postcondition");
    }

    private PlanExecuteEngine engineWithResponses(String... responses) {
        ToolRegistry registry = new ToolRegistry();
        registry.register("echo", "Echo", "{}", input -> "echo:" + input);
        return new PlanExecuteEngine(new QueueLlm(responses), registry, new AgentRuntimeRecorder());
    }

    private String validPlan(boolean parallel) {
        return """
                {"phases":[{"name":"collect","parallel":%s,"steps":[
                  {"tool":"echo","input":{"id":"a"},"description":"collect","outputKey":"a"}
                ]}]}
                """.formatted(parallel);
    }

    private static class QueueLlm implements AgentLlmClient {
        private final Queue<String> responses = new ArrayDeque<>();

        QueueLlm(String... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public String complete(String systemPrompt,
                               List<Message> history,
                               String userMessage,
                               Map<String, Object> advisorParams) {
            return responses.isEmpty() ? "" : responses.remove();
        }
    }
}
