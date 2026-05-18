package com.bridge.agent.checkpoint;

import java.util.List;
import java.util.Optional;

public interface CheckpointStore {

    AgentCheckpoint save(AgentCheckpoint checkpoint);

    Optional<AgentCheckpoint> latest(String runId);

    List<AgentCheckpoint> list(String runId);
}
