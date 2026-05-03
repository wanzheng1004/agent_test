package com.bridge.agent.memory;

import com.bridge.agent.memory.dto.NormalizedDefect;
import com.bridge.agent.util.JsonUtil;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话级记忆存储（三级记忆架构 — 会话级）
 *
 * <p>存储于 Redis，TTL = 8h（一次完整检测任务的生命周期）。
 * 管理两类数据：
 * <ul>
 *   <li>对话历史消息（支持上下文窗口管理）</li>
 *   <li>本次会话已规范化的病害记录列表</li>
 * </ul>
 *
 * <p>Key 设计：
 * <pre>
 * session:messages:{sessionId}  — List<JSON>
 * session:defects:{sessionId}   — List<JSON>
 * session:meta:{sessionId}      — Hash（bridgeId 等元数据）
 * </pre>
 */
@Component
public class SessionMemoryStore {

    private final RedisTemplate<String, String> redis;

    @Value("${bridge.agent.session.ttl-hours:8}")
    private int ttlHours;

    public SessionMemoryStore(RedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    // ==================== 对话历史 ====================

    /** 追加一条消息到会话历史 */
    public void appendMessage(String sessionId, Message message) {
        String key = "session:messages:" + sessionId;
        redis.opsForList().rightPush(key, serializeMessage(message));
        redis.expire(key, Duration.ofHours(ttlHours));
    }

    /** 获取会话完整历史 */
    public List<Message> getHistory(String sessionId) {
        String key = "session:messages:" + sessionId;
        List<String> raw = redis.opsForList().range(key, 0, -1);
        if (raw == null) return new ArrayList<>();
        return raw.stream().map(this::deserializeMessage).collect(Collectors.toList());
    }

    /** 获取指定范围的历史消息（用于滑动窗口） */
    public List<Message> getHistory(String sessionId, int start, int end) {
        String key = "session:messages:" + sessionId;
        List<String> raw = redis.opsForList().range(key, start, end);
        if (raw == null) return new ArrayList<>();
        return raw.stream().map(this::deserializeMessage).collect(Collectors.toList());
    }

    /** 获取历史消息总数 */
    public long getHistorySize(String sessionId) {
        String key = "session:messages:" + sessionId;
        Long size = redis.opsForList().size(key);
        return size != null ? size : 0;
    }

    /** 清空历史（压缩摘要后重建） */
    public void clearHistory(String sessionId) {
        redis.delete("session:messages:" + sessionId);
    }

    // ==================== 病害记录 ====================

    /** 追加规范化病害记录（检测中 normalize_defect 工具执行成功后调用） */
    public void appendDefect(String sessionId, NormalizedDefect defect) {
        String key = "session:defects:" + sessionId;
        redis.opsForList().rightPush(key, JsonUtil.toJson(defect));
        redis.expire(key, Duration.ofHours(ttlHours));
    }

    /** 获取本次会话所有病害记录 */
    public List<NormalizedDefect> getDefects(String sessionId) {
        String key = "session:defects:" + sessionId;
        List<String> raw = redis.opsForList().range(key, 0, -1);
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        return raw.stream()
                .map(s -> JsonUtil.parse(s, NormalizedDefect.class))
                .collect(Collectors.toList());
    }

    // ==================== 会话元数据 ====================

    /** 存储会话元数据（如当前操作的 bridgeId） */
    public void setMeta(String sessionId, String field, String value) {
        String key = "session:meta:" + sessionId;
        redis.opsForHash().put(key, field, value);
        redis.expire(key, Duration.ofHours(ttlHours));
    }

    public String getMeta(String sessionId, String field) {
        String key = "session:meta:" + sessionId;
        Object val = redis.opsForHash().get(key, field);
        return val != null ? val.toString() : null;
    }

    // ==================== 序列化 ====================

    private String serializeMessage(Message message) {
        String role = switch (message) {
            case UserMessage u -> "USER";
            case AssistantMessage a -> "ASSISTANT";
            case SystemMessage s -> "SYSTEM";
            default -> "USER";
        };
        return JsonUtil.toJson(new MessageDto(role, message.getText()));
    }

    private Message deserializeMessage(String json) {
        MessageDto dto = JsonUtil.parse(json, MessageDto.class);
        return switch (dto.role()) {
            case "ASSISTANT" -> new AssistantMessage(dto.content());
            case "SYSTEM"    -> new SystemMessage(dto.content());
            default          -> new UserMessage(dto.content());
        };
    }

    private record MessageDto(String role, String content) {}
}
