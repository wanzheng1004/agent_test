package com.bridge.agent.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@Profile("demo")
public class FileCheckpointStore implements CheckpointStore {

    private final Path checkpointDir;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ConcurrentMap<String, List<AgentCheckpoint>> cache = new ConcurrentHashMap<>();

    public FileCheckpointStore(@Value("${bridge.runtime.checkpoint-dir:data/checkpoints}") String checkpointDir) {
        this.checkpointDir = Path.of(checkpointDir);
    }

    @Override
    public AgentCheckpoint save(AgentCheckpoint checkpoint) {
        cache.compute(checkpoint.runId(), (runId, existing) -> {
            List<AgentCheckpoint> updated = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            updated.add(checkpoint);
            return updated;
        });
        append(checkpoint);
        return checkpoint;
    }

    @Override
    public Optional<AgentCheckpoint> latest(String runId) {
        return list(runId).stream()
                .max(Comparator.comparing(AgentCheckpoint::sequence));
    }

    @Override
    public List<AgentCheckpoint> list(String runId) {
        return cache.computeIfAbsent(runId, this::load);
    }

    private void append(AgentCheckpoint checkpoint) {
        try {
            Files.createDirectories(checkpointDir);
            Files.writeString(
                    checkpointFile(checkpoint.runId()),
                    mapper.writeValueAsString(checkpoint) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist checkpoint: " + e.getMessage(), e);
        }
    }

    private List<AgentCheckpoint> load(String runId) {
        Path file = checkpointFile(runId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<AgentCheckpoint> loaded = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    loaded.add(mapper.readValue(line, AgentCheckpoint.class));
                }
            }
            return loaded;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load checkpoints: " + e.getMessage(), e);
        }
    }

    private Path checkpointFile(String runId) {
        return checkpointDir.resolve(runId + ".jsonl");
    }
}
