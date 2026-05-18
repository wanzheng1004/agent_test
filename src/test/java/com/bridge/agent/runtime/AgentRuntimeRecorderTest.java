package com.bridge.agent.runtime;

import com.bridge.agent.core.AgentState;
import com.bridge.agent.core.TerminationReason;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeRecorderTest {

    @Test
    void recordsEventsAndCheckpointsInOrder() {
        AgentRuntimeRecorder recorder = new AgentRuntimeRecorder();
        AgentRun run = recorder.startRun("agent", "REACT", "s1", "hello");

        recorder.record(run.getRunId(), AgentEventType.LLM_STARTED, "start");
        recorder.saveCheckpoint(run.getRunId(), "AFTER_LLM", AgentState.RUNNING, null, Map.of("step", 1));
        recorder.finishRun(run.getRunId(), AgentState.FINISHED, TerminationReason.NORMAL_FINISH, "done");

        assertThat(recorder.getEvents(run.getRunId()))
                .extracting(AgentEvent::type)
                .contains(AgentEventType.RUN_STARTED, AgentEventType.CHECKPOINT_SAVED, AgentEventType.RUN_FINISHED);
        assertThat(recorder.getCheckpoints(run.getRunId()))
                .extracting(checkpoint -> checkpoint.stage())
                .contains("RUN_STARTED", "AFTER_LLM", "RUN_FINISHED");
        assertThat(recorder.latestCheckpoint(run.getRunId())).isPresent();
    }
}
