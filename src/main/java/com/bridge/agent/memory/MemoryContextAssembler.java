package com.bridge.agent.memory;

import com.bridge.agent.entity.BridgeMemoryEntity;
import com.bridge.agent.orchestrator.dto.SceneType;
import com.bridge.agent.runtime.AgentEventType;
import com.bridge.agent.runtime.AgentRuntimeRecorder;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MemoryContextAssembler {

    private final SessionMemoryStore sessionStore;
    private final BridgeMemoryService bridgeMemoryService;
    private final UserPreferenceService userPreferenceService;
    private final AgentRuntimeRecorder runtimeRecorder;

    public MemoryContextAssembler(SessionMemoryStore sessionStore,
                                  BridgeMemoryService bridgeMemoryService,
                                  UserPreferenceService userPreferenceService,
                                  AgentRuntimeRecorder runtimeRecorder) {
        this.sessionStore = sessionStore;
        this.bridgeMemoryService = bridgeMemoryService;
        this.userPreferenceService = userPreferenceService;
        this.runtimeRecorder = runtimeRecorder;
    }

    public MemoryContext assemble(String runId,
                                  String sessionId,
                                  String bridgeId,
                                  String userId,
                                  SceneType scene) {
        String shortTerm = "";
        String bridgeMemory = "";
        Map<String, Object> preferences = new LinkedHashMap<>();
        java.util.ArrayList<String> sections = new java.util.ArrayList<>();

        if (sessionId != null && !sessionId.isBlank()) {
            List<Message> history = sessionStore.getHistory(sessionId);
            shortTerm = "recentMessages=" + history.size();
            sections.add("short_term_session");
        }

        if (bridgeId != null && !bridgeId.isBlank()) {
            BridgeMemoryEntity memory = bridgeMemoryService.getMemory(bridgeId);
            if (memory != null) {
                bridgeMemory = bridgeMemoryService.formatMemory(memory);
                sections.add("long_term_bridge");
            }
        }

        if (userId != null && !userId.isBlank()) {
            preferences.put("outputFormat", userPreferenceService.getOutputFormat(userId));
            preferences.put("preferredBridges", userPreferenceService.getPreferredBridges(userId));
            sections.add("user_preference");
        }

        if (runId != null) {
            runtimeRecorder.record(runId, AgentEventType.MEMORY_READ,
                    "Memory context assembled", Map.of(
                            "sections", sections,
                            "scene", scene == null ? "" : scene.name()
                    ), 0);
        }

        return new MemoryContext(
                sessionId,
                bridgeId,
                userId,
                scene,
                shortTerm,
                bridgeMemory,
                preferences,
                List.copyOf(sections));
    }
}
