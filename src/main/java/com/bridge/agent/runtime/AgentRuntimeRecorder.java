package com.bridge.agent.runtime;

import com.bridge.agent.checkpoint.AgentCheckpoint;
import com.bridge.agent.checkpoint.CheckpointStore;
import com.bridge.agent.checkpoint.InMemoryCheckpointStore;
import com.bridge.agent.core.AgentState;
import com.bridge.agent.core.TerminationReason;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentRuntimeRecorder {

    private final Map<String, AgentRun> runs = new ConcurrentHashMap<>();
    private final CheckpointStore checkpointStore;

    public AgentRuntimeRecorder() {
        this(new InMemoryCheckpointStore());
    }

    @Autowired
    public AgentRuntimeRecorder(CheckpointStore checkpointStore) {
        this.checkpointStore = checkpointStore;
    }

    public AgentRun startRun(String agentName, String scene, String sessionId, String input) {
        String runId = UUID.randomUUID().toString();
        AgentRun run = new AgentRun(runId, sessionId, agentName, scene, Instant.now());
        runs.put(runId, run);
        record(runId, AgentEventType.RUN_STARTED, "Run started", Map.of(
                "inputLength", input == null ? 0 : input.length()
        ), 0);
        saveCheckpoint(runId, "RUN_STARTED", AgentState.RUNNING, null, Map.of(
                "agentName", safe(agentName),
                "scene", safe(scene),
                "sessionId", sessionId == null ? "" : sessionId,
                "inputLength", input == null ? 0 : input.length()
        ));
        return run;
    }

    public AgentEvent record(String runId, AgentEventType type, String message) {
        return record(runId, type, message, Map.of(), 0);
    }

    public AgentEvent record(String runId,
                             AgentEventType type,
                             String message,
                             Map<String, Object> attributes,
                             long elapsedMs) {
        AgentRun run = runs.get(runId);
        if (run == null) {
            return null;
        }
        AgentEvent event = new AgentEvent(
                UUID.randomUUID().toString(),
                runId,
                type,
                run.getAgentName(),
                message,
                attributes == null ? Map.of() : Map.copyOf(attributes),
                Instant.now(),
                elapsedMs
        );
        run.addEvent(event);
        return event;
    }

    public void finishRun(String runId,
                          AgentState state,
                          TerminationReason reason,
                          String finalOutput) {
        AgentRun run = runs.get(runId);
        if (run == null) {
            return;
        }
        run.finish(state, reason, finalOutput);
        long elapsed = Duration.between(run.getStartedAt(), Instant.now()).toMillis();
        record(runId, AgentEventType.RUN_FINISHED, "Run finished", Map.of(
                "state", state.name(),
                "terminationReason", reason == null ? "" : reason.name(),
                "answerLength", finalOutput == null ? 0 : finalOutput.length()
        ), elapsed);
        saveCheckpoint(runId, "RUN_FINISHED", state, reason, Map.of(
                "answerLength", finalOutput == null ? 0 : finalOutput.length()
        ));
    }

    public AgentCheckpoint saveCheckpoint(String runId,
                                          String stage,
                                          AgentState state,
                                          TerminationReason reason,
                                          Map<String, Object> payload) {
        AgentRun run = runs.get(runId);
        int sequence = run == null ? 0 : run.getEvents().size();
        AgentCheckpoint checkpoint = new AgentCheckpoint(
                UUID.randomUUID().toString(),
                runId,
                stage,
                sequence,
                state,
                reason,
                payload == null ? Map.of() : Map.copyOf(payload),
                Instant.now());
        AgentCheckpoint saved = checkpointStore.save(checkpoint);
        record(runId, AgentEventType.CHECKPOINT_SAVED, "Checkpoint saved", Map.of(
                "checkpointId", saved.checkpointId(),
                "stage", saved.stage(),
                "sequence", saved.sequence()
        ), 0);
        return saved;
    }

    public Optional<AgentCheckpoint> latestCheckpoint(String runId) {
        Optional<AgentCheckpoint> checkpoint = checkpointStore.latest(runId);
        checkpoint.ifPresent(value -> record(runId, AgentEventType.CHECKPOINT_LOADED,
                "Latest checkpoint loaded", Map.of(
                        "checkpointId", value.checkpointId(),
                        "stage", value.stage()
                ), 0));
        return checkpoint;
    }

    public List<AgentCheckpoint> getCheckpoints(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        return checkpointStore.list(runId);
    }

    public AgentRun getRun(String runId) {
        return runs.get(runId);
    }

    public List<AgentEvent> getEvents(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        AgentRun run = runs.get(runId);
        return run == null ? List.of() : run.getEvents();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
