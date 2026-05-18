package com.bridge.agent.controller;

import com.bridge.agent.core.TerminationReason;
import com.bridge.agent.orchestrator.AgentOrchestrator;
import com.bridge.agent.orchestrator.dto.AgentChatResult;
import com.bridge.agent.orchestrator.dto.ChatRequest;
import com.bridge.agent.orchestrator.dto.SceneType;
import com.bridge.agent.runtime.AgentEvent;
import com.bridge.agent.runtime.AgentEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentOrchestrator orchestrator;

    public AgentController(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        long start = System.currentTimeMillis();
        AgentChatResult result = orchestrator.handle(request);
        long elapsed = System.currentTimeMillis() - start;

        log.info("Chat response: sessionId={}, runId={}, scene={}, elapsed={}ms",
                request.sessionId(), result.runId(), result.scene(), elapsed);

        return new ChatResponse(
                request.sessionId(),
                result.answer(),
                elapsed,
                result.runId(),
                result.scene(),
                result.terminationReason());
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentEvent>> chatStream(@RequestBody ChatRequest request) {
        return Mono.fromCallable(() -> orchestrator.handle(request))
                .flatMapMany(result -> {
                    List<AgentEvent> events = result.events();
                    AgentEvent finalEvent = new AgentEvent(
                            UUID.randomUUID().toString(),
                            result.runId(),
                            AgentEventType.RUN_FINISHED,
                            result.scene() == null ? "Orchestrator" : result.scene().name(),
                            result.answer(),
                            Map.of("terminationReason",
                                    result.terminationReason() == null ? "" : result.terminationReason().name()),
                            Instant.now(),
                            0
                    );
                    if (events.isEmpty()) {
                        return Flux.just(finalEvent);
                    }
                    return Flux.fromIterable(events)
                            .filter(event -> event.type() != AgentEventType.RUN_FINISHED)
                            .concatWith(Mono.just(finalEvent));
                })
                .map(event -> ServerSentEvent.builder(event)
                        .id(event.eventId())
                        .event(event.type().name())
                        .build());
    }

    public record ChatResponse(
            String sessionId,
            String answer,
            long elapsedMs,
            String runId,
            SceneType scene,
            TerminationReason terminationReason
    ) {
    }
}
