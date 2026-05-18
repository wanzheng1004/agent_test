package com.bridge.agent.checkpoint;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@ConditionalOnMissingBean(CheckpointStore.class)
public class InMemoryCheckpointStore implements CheckpointStore {

    private final ConcurrentMap<String, List<AgentCheckpoint>> checkpoints = new ConcurrentHashMap<>();

    @Override
    public AgentCheckpoint save(AgentCheckpoint checkpoint) {
        checkpoints.compute(checkpoint.runId(), (runId, existing) -> {
            List<AgentCheckpoint> updated = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            updated.add(checkpoint);
            return updated;
        });
        return checkpoint;
    }

    @Override
    public Optional<AgentCheckpoint> latest(String runId) {
        return list(runId).stream()
                .max(Comparator.comparing(AgentCheckpoint::sequence));
    }

    @Override
    public List<AgentCheckpoint> list(String runId) {
        List<AgentCheckpoint> existing = checkpoints.get(runId);
        return existing == null ? List.of() : List.copyOf(existing);
    }
}
