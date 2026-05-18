package com.bridge.agent.core;

import com.bridge.agent.llm.AgentLlmClient;
import com.bridge.agent.runtime.AgentRuntimeRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReActEngineTest {

    @Test
    void finishesDirectly() {
        ReActEngine engine = engineWithResponses("""
                Thought: Done.
                Action: FINISH
                ActionInput: final answer
                """);

        AgentContext ctx = engine.execute("hello", List.of(), Set.of(), "prompt", "s1", "agent");

        assertThat(ctx.getFinalAnswer()).isEqualTo("final answer");
        assertThat(ctx.getTerminationReason()).isEqualTo(TerminationReason.NORMAL_FINISH);
    }

    @Test
    void asksUser() {
        ReActEngine engine = engineWithResponses("""
                Thought: Need more.
                Action: ASK_USER
                ActionInput: crack width?
                """);

        AgentContext ctx = engine.execute("crack", List.of(), Set.of(), "prompt", "s1", "agent");

        assertThat(ctx.getFinalAnswer()).isEqualTo("crack width?");
        assertThat(ctx.getTerminationReason()).isEqualTo(TerminationReason.WAITING_USER_INPUT);
    }

    @Test
    void selfCorrectsInvalidThought() {
        ReActEngine engine = engineWithResponses(
                "bad output",
                """
                        Thought: Corrected.
                        Action: FINISH
                        ActionInput: corrected answer
                        """);

        AgentContext ctx = engine.execute("x", List.of(), Set.of(), "prompt", "s1", "agent");

        assertThat(ctx.getFinalAnswer()).isEqualTo("corrected answer");
    }

    @Test
    void recordsInvalidToolAsObservation() {
        ReActEngine engine = engineWithResponses(
                """
                        Thought: Use missing.
                        Action: missing_tool
                        ActionInput: {}
                        """,
                """
                        Thought: Enough.
                        Action: FINISH
                        ActionInput: done
                        """);

        AgentContext ctx = engine.execute("x", List.of(), Set.of("missing_tool"), "prompt", "s1", "agent");

        assertThat(ctx.getSteps()).hasSize(1);
        assertThat(ctx.getSteps().get(0).status()).isEqualTo(StepStatus.INVALID_ACTION);
        assertThat(ctx.getFinalAnswer()).isEqualTo("done");
    }

    @Test
    void stopsAtMaxIterationsWithFallback() {
        ReActEngine engine = engineWithResponses(
                """
                        Thought: Call.
                        Action: echo
                        ActionInput: {"x":1}
                        """,
                "fallback answer");
        ReflectionTestUtils.setField(engine, "maxIterations", 1);

        AgentContext ctx = engine.execute("x", List.of(), Set.of("echo"), "prompt", "s1", "agent");

        assertThat(ctx.getTerminationReason()).isEqualTo(TerminationReason.MAX_ITERATIONS);
        assertThat(ctx.getFinalAnswer()).isEqualTo("fallback answer");
    }

    private ReActEngine engineWithResponses(String... responses) {
        ToolRegistry registry = new ToolRegistry();
        registry.register("echo", "Echo", "{}", input -> "echo:" + input);
        return new ReActEngine(
                new QueueLlm(responses),
                registry,
                new ThinkResultParser(),
                new AgentRuntimeRecorder());
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
