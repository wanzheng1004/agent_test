package com.bridge.agent.controller;

import com.bridge.agent.memory.SessionMemoryStore;
import com.bridge.agent.memory.dto.NormalizedDefect;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话管理 API
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final SessionMemoryStore sessionStore;

    public SessionController(SessionMemoryStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    /**
     * 获取本次会话已规范化的病害清单
     *
     * <p>检修后 Agent 汇总前，前端可先调此接口预览。
     * GET /api/session/{sessionId}/defects
     */
    @GetMapping("/{sessionId}/defects")
    public List<NormalizedDefect> getSessionDefects(@PathVariable String sessionId) {
        return sessionStore.getDefects(sessionId);
    }

    /**
     * 获取会话历史消息数量
     */
    @GetMapping("/{sessionId}/size")
    public long getSessionSize(@PathVariable String sessionId) {
        return sessionStore.getHistorySize(sessionId);
    }

    /**
     * 获取会话元数据（bridgeId 等）
     */
    @GetMapping("/{sessionId}/meta/{field}")
    public String getSessionMeta(@PathVariable String sessionId,
                                  @PathVariable String field) {
        return sessionStore.getMeta(sessionId, field);
    }
}
