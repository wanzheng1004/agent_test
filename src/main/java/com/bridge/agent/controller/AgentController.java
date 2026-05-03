package com.bridge.agent.controller;

import com.bridge.agent.orchestrator.AgentOrchestrator;
import com.bridge.agent.orchestrator.dto.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 主对话 API
 *
 * <p>提供两个端点：
 * <ul>
 *   <li>POST /api/agent/chat      — 普通 JSON 响应（简单场景）</li>
 *   <li>POST /api/agent/chat/stream — SSE 流式响应（推荐，用于前端实时显示）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*") // 开发环境允许跨域，生产环境配置具体域名
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentOrchestrator orchestrator;

    public AgentController(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * 主对话接口（同步 JSON）
     *
     * <p>请求示例：
     * <pre>
     * POST /api/agent/chat
     * {
     *   "sessionId": "sess-uuid-001",
     *   "userId": "user-001",
     *   "message": "BRG-001 沿江大桥，明天要去检测"
     * }
     * </pre>
     */
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        log.info("Chat request: sessionId={}, messageLen={}",
                request.sessionId(), request.message().length());

        long start = System.currentTimeMillis();
        String answer = orchestrator.chat(request);
        long elapsed = System.currentTimeMillis() - start;

        log.info("Chat response: sessionId={}, elapsed={}ms, answerLen={}",
                request.sessionId(), elapsed, answer.length());

        return new ChatResponse(request.sessionId(), answer, elapsed);
    }

    /**
     * 主对话接口（SSE 流式）
     *
     * <p>适合前端实时显示生成过程。
     * 当前实现：将完整响应拆分为字符流（真实流式需 Agent 支持 Flux 输出）。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest request) {
        return Mono.fromCallable(() -> orchestrator.chat(request))
                .flux()
                .flatMap(answer ->
                        // 按句子拆分，模拟流式输出效果
                        Flux.fromArray(answer.split("(?<=。|？|！|\\n)"))
                )
                .map(chunk -> chunk + "\n");
    }

    /** 响应 DTO */
    public record ChatResponse(String sessionId, String answer, long elapsedMs) {}
}
