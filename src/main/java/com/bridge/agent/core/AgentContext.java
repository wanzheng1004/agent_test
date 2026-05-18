package com.bridge.agent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable execution context for a ReAct agent run.
 */
public class AgentContext {

    private final String runId;
    private final String sessionId;
    private final String agentName;
    private final List<AgentStep> steps = new ArrayList<>();
    private final Map<String, Object> scratchpad = new HashMap<>();

    private AgentState state = AgentState.RUNNING;
    private TerminationReason terminationReason;
    private String finalAnswer;
    private boolean waitingForUser = false;

    public AgentContext(String sessionId, String agentName) {
        this(null, sessionId, agentName);
    }

    public AgentContext(String runId, String sessionId, String agentName) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.agentName = agentName;
    }

    public void addStep(AgentStep step) {
        steps.add(step);
    }

    public String getTrajectoryText() {
        if (steps.isEmpty()) {
            return "(no previous steps)";
        }
        StringBuilder sb = new StringBuilder();
        for (AgentStep step : steps) {
            sb.append(step.toTrajectoryText()).append(System.lineSeparator());
        }
        return sb.toString();
    }

    public int consecutiveErrorCount() {
        int count = 0;
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i).status() == StepStatus.SUCCESS) {
                break;
            }
            count++;
        }
        return count;
    }

    public void terminate(AgentState state, TerminationReason reason) {
        this.state = state;
        this.terminationReason = reason;
        this.waitingForUser = reason == TerminationReason.WAITING_USER_INPUT;
    }

    public void put(String key, Object value) {
        scratchpad.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) scratchpad.get(key);
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

    public List<AgentStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    public AgentState getState() {
        return state;
    }

    public TerminationReason getTerminationReason() {
        return terminationReason;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public boolean isWaitingForUser() {
        return waitingForUser;
    }

    public boolean isRunning() {
        return state == AgentState.RUNNING;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }
}
