package com.bridge.agent.runtime;

import com.bridge.agent.core.AgentState;
import com.bridge.agent.core.TerminationReason;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class AgentRun {

    private final String runId;
    private final String sessionId;
    private final String agentName;
    private final String scene;
    private final Instant startedAt;
    private final List<AgentEvent> events = new CopyOnWriteArrayList<>();

    private Instant endedAt;
    private AgentState state = AgentState.RUNNING;
    private TerminationReason terminationReason;
    private String finalOutput;

    AgentRun(String runId, String sessionId, String agentName, String scene, Instant startedAt) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.agentName = agentName;
        this.scene = scene;
        this.startedAt = startedAt;
    }

    void addEvent(AgentEvent event) {
        events.add(event);
    }

    void finish(AgentState state, TerminationReason terminationReason, String finalOutput) {
        this.state = state;
        this.terminationReason = terminationReason;
        this.finalOutput = finalOutput;
        this.endedAt = Instant.now();
    }

    public String getRunId() {
        return runId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAgentName() {
        return agentName;
    }

    public String getScene() {
        return scene;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public AgentState getState() {
        return state;
    }

    public TerminationReason getTerminationReason() {
        return terminationReason;
    }

    public String getFinalOutput() {
        return finalOutput;
    }

    public List<AgentEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }
}
