package com.bridge.agent.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON 工具类 —— 统一 Jackson 操作，避免散落的异常处理
 */
public class JsonUtil {

    private static final Logger log = LoggerFactory.getLogger(JsonUtil.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private JsonUtil() {}

    /** 将对象序列化为 JSON 字符串 */
    public static String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON serialization failed: " + e.getMessage(), e);
        }
    }

    /** 将 JSON 字符串反序列化为指定类型 */
    public static <T> T parse(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("JSON parse failed for type {}: {}", type.getSimpleName(), e.getMessage());
            throw new IllegalArgumentException(
                    "JSON parse failed for " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /** 安全读取 JSON 字段（字段不存在时返回 null） */
    public static String getString(String json, String fieldName) {
        try {
            JsonNode node = mapper.readTree(json);
            JsonNode field = node.get(fieldName);
            return field != null && !field.isNull() ? field.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 安全读取 JSON 整型字段 */
    public static Integer getInt(String json, String fieldName) {
        try {
            JsonNode node = mapper.readTree(json);
            JsonNode field = node.get(fieldName);
            return field != null && !field.isNull() ? field.asInt() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 将 JSON 解析为 JsonNode（用于复杂结构访问） */
    public static JsonNode toTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    /** 格式化输出 JSON（调试用） */
    public static String pretty(Object obj) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }
}
