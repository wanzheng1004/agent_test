package com.bridge.agent.memory;

import com.bridge.agent.entity.BridgeMemoryEntity;
import com.bridge.agent.memory.dto.NormalizedDefect;
import com.bridge.agent.repository.BridgeMemoryRepository;
import com.bridge.agent.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 桥梁级记忆服务（三级记忆架构 — 桥梁级）
 *
 * <p>会话结束后，将本次检测的病害记录归档到 MySQL bridge_memory 表，
 * 更新 tracking_defects 中的病害发展数据点。
 *
 * <p>核心价值：
 * tracking_defects 记录了每次检测该病害的宽度/等级，
 * 天然形成了病害发展时间序列，支持"某病害近年有无扩展"的追溯查询，
 * 无需每次重新汇总历史记录。
 */
@Service
public class BridgeMemoryService {

    private static final Logger log = LoggerFactory.getLogger(BridgeMemoryService.class);
    private static final int MAX_HISTORY_RECORDS = 5; // 保留最近 N 次检测摘要
    private static final ObjectMapper mapper = new ObjectMapper();

    private final BridgeMemoryRepository repo;
    private final SessionMemoryStore sessionStore;

    public BridgeMemoryService(BridgeMemoryRepository repo,
                                 SessionMemoryStore sessionStore) {
        this.repo = repo;
        this.sessionStore = sessionStore;
    }

    /**
     * 获取桥梁级记忆（用于 MemoryAdvisor 注入上下文）
     */
    public BridgeMemoryEntity getMemory(String bridgeId) {
        return repo.findById(bridgeId).orElse(null);
    }

    /**
     * 将本次会话的病害记录归档到桥梁级记忆
     *
     * <p>由检修后 Agent 或会话结束时触发。
     */
    @Transactional
    public void archiveSession(String sessionId, String bridgeId) {
        List<NormalizedDefect> defects = sessionStore.getDefects(sessionId);
        if (defects.isEmpty()) {
            log.info("No defects to archive for session={}", sessionId);
            return;
        }

        BridgeMemoryEntity memory = repo.findById(bridgeId)
                .orElseGet(() -> {
                    BridgeMemoryEntity e = new BridgeMemoryEntity();
                    e.setBridgeId(bridgeId);
                    return e;
                });

        updateTrackingDefects(memory, defects);
        updateInspectionHistory(memory, sessionId, defects);
        memory.setLastInspection(LocalDate.now());
        memory.setHealthScore(calculateHealthScore(defects));

        repo.save(memory);
        log.info("Archived {} defects for bridge={}, session={}", defects.size(), bridgeId, sessionId);
    }

    /**
     * 格式化桥梁级记忆为 LLM 可读文本（注入 MemoryAdvisor）
     */
    public String formatMemory(BridgeMemoryEntity memory) {
        if (memory == null) return "（无历史记忆）";

        StringBuilder sb = new StringBuilder();
        sb.append("最近一次检测：").append(memory.getLastInspection()).append("\n");
        sb.append("综合健康评分：").append(memory.getHealthScore()).append("/100\n");

        if (memory.getTrackingDefects() != null) {
            sb.append("持续追踪病害：\n");
            try {
                JsonNode defects = mapper.readTree(memory.getTrackingDefects());
                if (defects.isArray()) {
                    for (JsonNode d : defects) {
                        sb.append(String.format("  - %s（%s）：首次发现 %s，趋势：%s，最新等级：%s类\n",
                                d.path("type").asText(),
                                d.path("component").asText(),
                                d.path("firstFound").asText(),
                                d.path("trend").asText("未知"),
                                getLatestGrade(d)));
                    }
                }
            } catch (Exception e) {
                sb.append("  （历史数据解析异常）\n");
            }
        }
        return sb.toString();
    }

    // ==================== 内部方法 ====================

    private void updateTrackingDefects(BridgeMemoryEntity memory,
                                        List<NormalizedDefect> newDefects) {
        try {
            ArrayNode tracking = memory.getTrackingDefects() != null
                    ? (ArrayNode) mapper.readTree(memory.getTrackingDefects())
                    : mapper.createArrayNode();

            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

            for (NormalizedDefect defect : newDefects) {
                if (defect.getGrade() != null && defect.getGrade() >= 2) {
                    // 查找已有追踪记录
                    boolean found = false;
                    for (JsonNode existing : tracking) {
                        if (defect.getDefectType().equals(existing.path("type").asText())
                                && defect.getComponent().equals(existing.path("component").asText())) {
                            // 追加发展数据点
                            ArrayNode history = (ArrayNode) existing.path("history");
                            ObjectNode dataPoint = mapper.createObjectNode();
                            dataPoint.put("date", today);
                            dataPoint.put("grade", defect.getGrade());
                            history.add(dataPoint);
                            ((ObjectNode) existing).put("trend",
                                    determineTrend(existing.path("history")));
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        // 新建追踪记录
                        ObjectNode newTrack = mapper.createObjectNode();
                        newTrack.put("type", defect.getDefectType());
                        newTrack.put("component", defect.getComponent());
                        newTrack.put("firstFound", today);
                        newTrack.put("trend", "初次发现");
                        ArrayNode history = newTrack.putArray("history");
                        ObjectNode dataPoint = mapper.createObjectNode();
                        dataPoint.put("date", today);
                        dataPoint.put("grade", defect.getGrade());
                        history.add(dataPoint);
                        tracking.add(newTrack);
                    }
                }
            }
            memory.setTrackingDefects(mapper.writeValueAsString(tracking));
        } catch (Exception e) {
            log.error("Failed to update tracking defects: {}", e.getMessage());
        }
    }

    private void updateInspectionHistory(BridgeMemoryEntity memory,
                                          String sessionId,
                                          List<NormalizedDefect> defects) {
        try {
            ArrayNode history = memory.getInspectionHistory() != null
                    ? (ArrayNode) mapper.readTree(memory.getInspectionHistory())
                    : mapper.createArrayNode();

            int maxGrade = defects.stream()
                    .filter(d -> d.getGrade() != null)
                    .mapToInt(NormalizedDefect::getGrade)
                    .max().orElse(0);

            ObjectNode record = mapper.createObjectNode();
            record.put("date", LocalDate.now().toString());
            record.put("sessionId", sessionId);
            record.put("defectCount", defects.size());
            record.put("maxGrade", maxGrade);
            record.put("summary", String.format("发现病害 %d 处，最高等级 %d 类", defects.size(), maxGrade));
            history.add(record);

            // 只保留最近 MAX_HISTORY_RECORDS 条
            while (history.size() > MAX_HISTORY_RECORDS) {
                history.remove(0);
            }
            memory.setInspectionHistory(mapper.writeValueAsString(history));
        } catch (Exception e) {
            log.error("Failed to update inspection history: {}", e.getMessage());
        }
    }

    private BigDecimal calculateHealthScore(List<NormalizedDefect> defects) {
        if (defects.isEmpty()) return BigDecimal.valueOf(100);
        double totalDeduction = defects.stream()
                .filter(d -> d.getGrade() != null)
                .mapToDouble(d -> switch (d.getGrade()) {
                    case 1 -> 2.0;
                    case 2 -> 5.0;
                    case 3 -> 15.0;
                    case 4 -> 30.0;
                    default -> 0.0;
                }).sum();
        return BigDecimal.valueOf(Math.max(0, 100 - totalDeduction));
    }

    private String determineTrend(JsonNode history) {
        if (!history.isArray() || history.size() < 2) return "观察中";
        JsonNode last = history.get(history.size() - 1);
        JsonNode prev = history.get(history.size() - 2);
        int lastGrade = last.path("grade").asInt(0);
        int prevGrade = prev.path("grade").asInt(0);
        if (lastGrade > prevGrade) return "扩展中";
        if (lastGrade < prevGrade) return "好转";
        return "稳定";
    }

    private String getLatestGrade(JsonNode defect) {
        JsonNode history = defect.path("history");
        if (history.isArray() && history.size() > 0) {
            return String.valueOf(history.get(history.size() - 1).path("grade").asInt());
        }
        return "未知";
    }
}
