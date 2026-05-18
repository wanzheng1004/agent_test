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

@Component
public class SessionMemoryStore {

    private final RedisTemplate<String, String> redis;

    @Value("${bridge.agent.session.ttl-hours:8}")
    private int ttlHours = 8;

    public SessionMemoryStore(RedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    public void appendMessage(String sessionId, Message message) {
        String key = "session:messages:" + sessionId;
        redis.opsForList().rightPush(key, serializeMessage(message));
        redis.expire(key, Duration.ofHours(ttlHours));
    }

    public List<Message> getHistory(String sessionId) {
        String key = "session:messages:" + sessionId;
        List<String> raw = redis.opsForList().range(key, 0, -1);
        if (raw == null) {
            return new ArrayList<>();
        }
        return raw.stream().map(this::deserializeMessage).collect(Collectors.toList());
    }

    public List<Message> getHistory(String sessionId, int start, int end) {
        String key = "session:messages:" + sessionId;
        List<String> raw = redis.opsForList().range(key, start, end);
        if (raw == null) {
            return new ArrayList<>();
        }
        return raw.stream().map(this::deserializeMessage).collect(Collectors.toList());
    }

    public long getHistorySize(String sessionId) {
        String key = "session:messages:" + sessionId;
        Long size = redis.opsForList().size(key);
        return size == null ? 0 : size;
    }

    public void clearHistory(String sessionId) {
        redis.delete("session:messages:" + sessionId);
    }

    public void appendDefect(String sessionId, NormalizedDefect defect) {
        String key = "session:defects:" + sessionId;
        redis.opsForList().rightPush(key, JsonUtil.toJson(defect));
        redis.expire(key, Duration.ofHours(ttlHours));
    }

    public List<NormalizedDefect> getDefects(String sessionId) {
        String key = "session:defects:" + sessionId;
        List<String> raw = redis.opsForList().range(key, 0, -1);
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        return raw.stream()
                .map(s -> JsonUtil.parse(s, NormalizedDefect.class))
                .collect(Collectors.toList());
    }

    public void setMeta(String sessionId, String field, String value) {
        String key = "session:meta:" + sessionId;
        redis.opsForHash().put(key, field, value);
        redis.expire(key, Duration.ofHours(ttlHours));
    }

    public String getMeta(String sessionId, String field) {
        String key = "session:meta:" + sessionId;
        Object val = redis.opsForHash().get(key, field);
        return val == null ? null : val.toString();
    }

    private String serializeMessage(Message message) {
        String role;
        if (message instanceof AssistantMessage) {
            role = "ASSISTANT";
        } else if (message instanceof SystemMessage) {
            role = "SYSTEM";
        } else {
            role = "USER";
        }
        return JsonUtil.toJson(new MessageDto(role, message.getText()));
    }

    private Message deserializeMessage(String json) {
        MessageDto dto = JsonUtil.parse(json, MessageDto.class);
        return switch (dto.role()) {
            case "ASSISTANT" -> new AssistantMessage(dto.content());
            case "SYSTEM" -> new SystemMessage(dto.content());
            default -> new UserMessage(dto.content());
        };
    }

    private record MessageDto(String role, String content) {
    }
}
