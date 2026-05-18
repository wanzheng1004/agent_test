package com.bridge.agent.controller;

import com.bridge.agent.core.TerminationReason;
import com.bridge.agent.orchestrator.AgentOrchestrator;
import com.bridge.agent.orchestrator.dto.AgentChatResult;
import com.bridge.agent.orchestrator.dto.SceneType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentOrchestrator orchestrator;

    @Test
    void chatResponseKeepsLegacyFieldsAndAddsRunMetadata() throws Exception {
        when(orchestrator.handle(any())).thenReturn(new AgentChatResult(
                "s1",
                "answer",
                "run-1",
                SceneType.GENERAL,
                TerminationReason.NORMAL_FINISH,
                List.of()));

        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"s1","userId":"u1","message":"hello"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s1"))
                .andExpect(jsonPath("$.answer").value("answer"))
                .andExpect(jsonPath("$.elapsedMs").isNumber())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.scene").value("GENERAL"))
                .andExpect(jsonPath("$.terminationReason").value("NORMAL_FINISH"));
    }
}
