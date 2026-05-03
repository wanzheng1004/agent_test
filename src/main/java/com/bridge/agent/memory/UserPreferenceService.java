package com.bridge.agent.memory;

import com.bridge.agent.entity.UserPreference;
import com.bridge.agent.repository.UserPreferenceRepository;
import com.bridge.agent.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户级记忆服务（三级记忆架构 — 用户级）
 *
 * <p>管理用户个性化偏好，供以下场景使用：
 * <ul>
 *   <li>意图分类前：读取惯用术语映射，做同义词替换</li>
 *   <li>Agent 输出时：读取偏好格式，调整报告详略</li>
 *   <li>前端首页：读取常用桥梁列表，快速访问</li>
 * </ul>
 *
 * <p>与会话级（Redis）和桥梁级（MySQL）的区别：
 * 用户级记忆关注"这个人怎么工作"，而非"这座桥什么状态"。
 */
@Service
public class UserPreferenceService {

    private static final Logger log = LoggerFactory.getLogger(UserPreferenceService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final UserPreferenceRepository repo;

    public UserPreferenceService(UserPreferenceRepository repo) {
        this.repo = repo;
    }

    /**
     * 获取或初始化用户偏好
     */
    public UserPreference getOrCreate(String userId) {
        return repo.findById(userId).orElseGet(() -> {
            UserPreference pref = new UserPreference();
            pref.setUserId(userId);
            return repo.save(pref);
        });
    }

    /**
     * 将桥梁加入用户常用桥梁列表（首页快速访问）
     */
    @Transactional
    public void addPreferredBridge(String userId, String bridgeId) {
        UserPreference pref = getOrCreate(userId);
        List<String> bridges = getPreferredBridgeList(pref);
        if (!bridges.contains(bridgeId)) {
            bridges.add(0, bridgeId); // 最近使用的排在最前
            if (bridges.size() > 10) bridges = bridges.subList(0, 10); // 最多保留 10 个
            try {
                pref.setPreferredBridges(mapper.writeValueAsString(bridges));
                repo.save(pref);
                log.debug("Added preferred bridge: userId={}, bridgeId={}", userId, bridgeId);
            } catch (Exception e) {
                log.error("Failed to update preferred bridges: {}", e.getMessage());
            }
        }
    }

    /**
     * 获取用户常用桥梁列表
     */
    public List<String> getPreferredBridges(String userId) {
        return repo.findById(userId)
                .map(this::getPreferredBridgeList)
                .orElse(new ArrayList<>());
    }

    /**
     * 应用用户惯用术语映射，提高意图分类准确率
     *
     * <p>例如：用户习惯说"裂纹"，mapping 将其替换为规范术语"裂缝"，
     * 避免意图分类因非标准术语导致召回不准。
     */
    public String applyTerminologyMapping(String userId, String text) {
        return repo.findById(userId)
                .filter(p -> p.getTerminology() != null)
                .map(p -> {
                    try {
                        JsonNode mapping = mapper.readTree(p.getTerminology());
                        String result = text;
                        var fields = mapping.fields();
                        while (fields.hasNext()) {
                            var entry = fields.next();
                            result = result.replace(entry.getKey(), entry.getValue().asText());
                        }
                        return result;
                    } catch (Exception e) {
                        return text;
                    }
                })
                .orElse(text);
    }

    /**
     * 更新用户偏好输出格式
     *
     * @param format brief / standard / detailed
     */
    @Transactional
    public void setOutputFormat(String userId, String format) {
        UserPreference pref = getOrCreate(userId);
        pref.setOutputFormat(format);
        repo.save(pref);
    }

    /**
     * 获取用户偏好格式（默认 standard）
     */
    public String getOutputFormat(String userId) {
        return repo.findById(userId)
                .map(p -> p.getOutputFormat() != null ? p.getOutputFormat() : "standard")
                .orElse("standard");
    }

    /**
     * 更新术语映射（合并更新，不覆盖原有映射）
     */
    @Transactional
    public void addTerminologyMapping(String userId, String nonStandardTerm,
                                       String standardTerm) {
        UserPreference pref = getOrCreate(userId);
        try {
            com.fasterxml.jackson.databind.node.ObjectNode mapping = pref.getTerminology() != null
                    ? (com.fasterxml.jackson.databind.node.ObjectNode)
                        mapper.readTree(pref.getTerminology())
                    : mapper.createObjectNode();
            mapping.put(nonStandardTerm, standardTerm);
            pref.setTerminology(mapper.writeValueAsString(mapping));
            repo.save(pref);
        } catch (Exception e) {
            log.error("Failed to update terminology mapping: {}", e.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    private List<String> getPreferredBridgeList(UserPreference pref) {
        if (pref.getPreferredBridges() == null) return new ArrayList<>();
        try {
            ArrayNode arr = (ArrayNode) mapper.readTree(pref.getPreferredBridges());
            List<String> result = new ArrayList<>();
            arr.forEach(n -> result.add(n.asText()));
            return result;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
