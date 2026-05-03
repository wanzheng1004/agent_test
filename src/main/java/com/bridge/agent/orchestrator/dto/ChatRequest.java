package com.bridge.agent.orchestrator.dto;

/**
 * 对话请求 DTO
 */
public record ChatRequest(
        String sessionId,   // 会话 ID（前端生成 UUID，首次对话时新建）
        String userId,      // 用户 ID
        String message      // 用户输入消息
) {}
