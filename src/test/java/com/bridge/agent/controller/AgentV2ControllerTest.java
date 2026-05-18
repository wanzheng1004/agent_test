package com.bridge.agent.controller;

import com.bridge.agent.core.ToolExecutionResult;
import com.bridge.agent.core.ToolExecutorRuntime;
import com.bridge.agent.core.StepStatus;
import com.bridge.agent.orchestrator.AgentOrchestrator;
import com.bridge.agent.orchestrator.dto.AgentChatResult;
import com.bridge.agent.orchestrator.dto.SceneType;
import com.bridge.agent.runtime.AgentRuntimeRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentV2Controller.class)
class AgentV2ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentOrchestrator orchestrator;

    @MockitoBean
    private AgentRuntimeRecorder runtimeRecorder;

    @MockitoBean
    private ToolExecutorRuntime toolRuntime;

    @Test
    void createRunReturnsV2Contract() throws Exception {
        when(orchestrator.handle(any())).thenReturn(new AgentChatResult(
                "s1", "answer", "run-1", SceneType.GENERAL, null, List.of()));
        when(runtimeRecorder.getCheckpoints(eq("run-1"))).thenReturn(List.of());

        mockMvc.perform(post("/api/v2/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"s1","userId":"u1","message":"hello"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.scene").value("GENERAL"))
                .andExpect(jsonPath("$.answer").value("answer"))
                .andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.checkpoints").isArray());
    }

    @Test
    void eventsForUnknownRunReturnsEmptyList() throws Exception {
        when(runtimeRecorder.getEvents("missing")).thenReturn(List.of());

        mockMvc.perform(get("/api/v2/agent/runs/missing/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void resumeUnknownRunReturnsNotFound() throws Exception {
        when(runtimeRecorder.getRun("missing")).thenReturn(null);
        when(toolRuntime.resumeApproval(eq("missing"), any())).thenReturn(
                new ToolExecutionResult("", "none", StepStatus.INVALID_ACTION, 0, null));

        mockMvc.perform(post("/api/v2/agent/runs/missing/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"approve\"}"))
                .andExpect(status().isNotFound());
    }
}
