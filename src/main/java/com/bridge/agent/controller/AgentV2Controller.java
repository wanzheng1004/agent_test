package com.bridge.agent.controller;

import com.bridge.agent.core.ToolExecutorRuntime;
import com.bridge.agent.orchestrator.AgentOrchestrator;
import com.bridge.agent.orchestrator.dto.AgentRunRequest;
import com.bridge.agent.orchestrator.dto.AgentRunResponse;
import com.bridge.agent.orchestrator.dto.AgentRunView;
import com.bridge.agent.orchestrator.dto.AgentChatResult;
import com.bridge.agent.orchestrator.dto.ResumeRunRequest;
import com.bridge.agent.runtime.AgentEvent;
import com.bridge.agent.runtime.AgentEventType;
import com.bridge.agent.runtime.AgentRun;
import com.bridge.agent.runtime.AgentRuntimeRecorder;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/agent")
@CrossOrigin(origins = "*")
public class AgentV2Controller {

    private final AgentOrchestrator orchestrator;
    private final AgentRuntimeRecorder runtimeRecorder;
    private final ToolExecutorRuntime toolRuntime;

    public AgentV2Controller(AgentOrchestrator orchestrator,
                             AgentRuntimeRecorder runtimeRecorder,
                             ToolExecutorRuntime toolRuntime) {
        this.orchestrator = orchestrator;
        this.runtimeRecorder = runtimeRecorder;
        this.toolRuntime = toolRuntime;
    }

    @PostMapping("/runs")
    public AgentRunResponse createRun(@RequestBody AgentRunRequest request) {
        AgentChatResult result = orchestrator.handle(request.toChatRequest());
        return toResponse(result);
    }

    @PostMapping(value = "/runs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentEvent>> streamRun(@RequestBody AgentRunRequest request) {
        return Mono.fromCallable(() -> orchestrator.handle(request.toChatRequest()))
                .flatMapMany(result -> {
                    List<AgentEvent> events = result.events();
                    if (events != null && !events.isEmpty()) {
                        return Flux.fromIterable(events);
                    }
                    AgentEvent synthetic = new AgentEvent(
                            UUID.randomUUID().toString(),
                            result.runId(),
                            AgentEventType.RUN_FINISHED,
                            result.scene() == null ? "Orchestrator" : result.scene().name(),
                            result.answer(),
                            Map.of("terminationReason",
                                    result.terminationReason() == null ? "" : result.terminationReason().name()),
                            java.time.Instant.now(),
                            0);
                    return Flux.just(synthetic);
                })
                .map(event -> ServerSentEvent.builder(event)
                        .id(event.eventId())
                        .event(event.type().name())
                        .build());
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<AgentRunView> getRun(@PathVariable String runId) {
        AgentRun run = runtimeRecorder.getRun(runId);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toView(run));
    }

    @GetMapping("/runs/{runId}/events")
    public List<AgentEvent> getRunEvents(@PathVariable String runId) {
        return runtimeRecorder.getEvents(runId);
    }

    @PostMapping("/runs/{runId}/resume")
    public ResponseEntity<AgentRunView> resumeRun(@PathVariable String runId,
                                                  @RequestBody ResumeRunRequest request) {
        if (runtimeRecorder.getRun(runId) == null) {
            return ResponseEntity.notFound().build();
        }
        runtimeRecorder.record(runId, AgentEventType.RUN_RESUMED,
                "Run resume requested", Map.of(
                        "action", request.action() == null ? "" : request.action(),
                        "userId", request.userId() == null ? "" : request.userId()
                ), 0);
        toolRuntime.resumeApproval(runId, request.toDecision());
        return ResponseEntity.ok(toView(runtimeRecorder.getRun(runId)));
    }

    private AgentRunResponse toResponse(AgentChatResult result) {
        return new AgentRunResponse(
                result.sessionId(),
                result.runId(),
                result.scene(),
                result.terminationReason(),
                result.answer(),
                result.events(),
                runtimeRecorder.getCheckpoints(result.runId()));
    }

    private AgentRunView toView(AgentRun run) {
        return new AgentRunView(
                run.getRunId(),
                run.getSessionId(),
                run.getAgentName(),
                run.getScene(),
                run.getState(),
                run.getTerminationReason(),
                run.getFinalOutput(),
                run.getStartedAt(),
                run.getEndedAt(),
                runtimeRecorder.getEvents(run.getRunId()),
                runtimeRecorder.getCheckpoints(run.getRunId()));
    }
}
