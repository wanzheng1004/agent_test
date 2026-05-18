package com.bridge.agent.checkpoint;

import com.bridge.agent.core.AgentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileCheckpointStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsAndReloadsCheckpointsAsJsonl() {
        FileCheckpointStore store = new FileCheckpointStore(tempDir.toString());
        AgentCheckpoint checkpoint = new AgentCheckpoint(
                "cp-1",
                "run-1",
                "AFTER_TOOL",
                1,
                AgentState.RUNNING,
                null,
                Map.of("tool", "lookup"),
                Instant.now());

        store.save(checkpoint);
        FileCheckpointStore reloaded = new FileCheckpointStore(tempDir.toString());

        assertThat(reloaded.list("run-1")).hasSize(1);
        assertThat(reloaded.latest("run-1")).isPresent();
        assertThat(reloaded.latest("run-1").get().stage()).isEqualTo("AFTER_TOOL");
    }
}
