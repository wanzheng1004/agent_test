package com.bridge.agent.memory;

import com.bridge.agent.entity.BridgeMemoryEntity;
import com.bridge.agent.memory.dto.NormalizedDefect;
import com.bridge.agent.repository.BridgeMemoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

@Service
public class BridgeMemoryService {

    private static final Logger log = LoggerFactory.getLogger(BridgeMemoryService.class);
    private static final int MAX_HISTORY_RECORDS = 5;
    private static final ObjectMapper mapper = new ObjectMapper();

    private final BridgeMemoryRepository repo;
    private final SessionMemoryStore sessionStore;

    public BridgeMemoryService(BridgeMemoryRepository repo,
                               SessionMemoryStore sessionStore) {
        this.repo = repo;
        this.sessionStore = sessionStore;
    }

    public BridgeMemoryEntity getMemory(String bridgeId) {
        return repo.findById(bridgeId).orElse(null);
    }

    @Transactional
    public void archiveSession(String sessionId, String bridgeId) {
        List<NormalizedDefect> defects = sessionStore.getDefects(sessionId);
        if (defects.isEmpty()) {
            log.info("No defects to archive for session={}", sessionId);
            return;
        }

        BridgeMemoryEntity memory = repo.findById(bridgeId)
                .orElseGet(() -> {
                    BridgeMemoryEntity entity = new BridgeMemoryEntity();
                    entity.setBridgeId(bridgeId);
                    return entity;
                });

        updateTrackingDefects(memory, defects);
        updateInspectionHistory(memory, sessionId, defects);
        memory.setLastInspection(LocalDate.now());
        memory.setHealthScore(calculateHealthScore(defects));

        repo.save(memory);
        log.info("Archived {} defects for bridge={}, session={}", defects.size(), bridgeId, sessionId);
    }

    public String formatMemory(BridgeMemoryEntity memory) {
        if (memory == null) {
            return "(no bridge memory)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Last inspection: ").append(memory.getLastInspection()).append('\n');
        sb.append("Health score: ").append(memory.getHealthScore()).append("/100\n");

        if (memory.getTrackingDefects() != null) {
            sb.append("Tracked defects:\n");
            try {
                JsonNode defects = mapper.readTree(memory.getTrackingDefects());
                if (defects.isArray()) {
                    for (JsonNode defect : defects) {
                        sb.append(String.format("  - %s (%s): first found %s, trend %s, latest grade %s%n",
                                defect.path("type").asText(),
                                defect.path("component").asText(),
                                defect.path("firstFound").asText(),
                                defect.path("trend").asText("unknown"),
                                getLatestGrade(defect)));
                    }
                }
            } catch (Exception e) {
                sb.append("  (tracking data parse failed)\n");
            }
        }
        return sb.toString();
    }

    private void updateTrackingDefects(BridgeMemoryEntity memory,
                                       List<NormalizedDefect> newDefects) {
        try {
            ArrayNode tracking = memory.getTrackingDefects() != null
                    ? (ArrayNode) mapper.readTree(memory.getTrackingDefects())
                    : mapper.createArrayNode();
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

            for (NormalizedDefect defect : newDefects) {
                if (defect.getGrade() == null || defect.getGrade() < 2) {
                    continue;
                }
                JsonNode existing = findTrackingNode(tracking, defect);
                if (existing != null) {
                    ArrayNode history = ensureHistoryArray((ObjectNode) existing);
                    ObjectNode dataPoint = mapper.createObjectNode();
                    dataPoint.put("date", today);
                    dataPoint.put("grade", defect.getGrade());
                    history.add(dataPoint);
                    ((ObjectNode) existing).put("trend", determineTrend(history));
                } else {
                    ObjectNode newTrack = mapper.createObjectNode();
                    newTrack.put("type", defect.getDefectType());
                    newTrack.put("component", defect.getComponent());
                    newTrack.put("firstFound", today);
                    newTrack.put("trend", "new");
                    ArrayNode history = newTrack.putArray("history");
                    ObjectNode dataPoint = mapper.createObjectNode();
                    dataPoint.put("date", today);
                    dataPoint.put("grade", defect.getGrade());
                    history.add(dataPoint);
                    tracking.add(newTrack);
                }
            }
            memory.setTrackingDefects(mapper.writeValueAsString(tracking));
        } catch (Exception e) {
            log.error("Failed to update tracking defects: {}", e.getMessage());
        }
    }

    private JsonNode findTrackingNode(ArrayNode tracking, NormalizedDefect defect) {
        for (JsonNode existing : tracking) {
            if (Objects.equals(defect.getDefectType(), existing.path("type").asText())
                    && Objects.equals(defect.getComponent(), existing.path("component").asText())) {
                return existing;
            }
        }
        return null;
    }

    private ArrayNode ensureHistoryArray(ObjectNode node) {
        JsonNode history = node.path("history");
        if (history.isArray()) {
            return (ArrayNode) history;
        }
        return node.putArray("history");
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
                    .max()
                    .orElse(0);

            ObjectNode record = mapper.createObjectNode();
            record.put("date", LocalDate.now().toString());
            record.put("sessionId", sessionId);
            record.put("defectCount", defects.size());
            record.put("maxGrade", maxGrade);
            record.put("summary", String.format("Detected %d defects, max grade %d", defects.size(), maxGrade));
            history.add(record);

            while (history.size() > MAX_HISTORY_RECORDS) {
                history.remove(0);
            }
            memory.setInspectionHistory(mapper.writeValueAsString(history));
        } catch (Exception e) {
            log.error("Failed to update inspection history: {}", e.getMessage());
        }
    }

    private BigDecimal calculateHealthScore(List<NormalizedDefect> defects) {
        if (defects.isEmpty()) {
            return BigDecimal.valueOf(100);
        }
        double totalDeduction = defects.stream()
                .filter(d -> d.getGrade() != null)
                .mapToDouble(d -> switch (d.getGrade()) {
                    case 1 -> 2.0;
                    case 2 -> 5.0;
                    case 3 -> 15.0;
                    case 4 -> 30.0;
                    default -> 0.0;
                })
                .sum();
        return BigDecimal.valueOf(Math.max(0, 100 - totalDeduction));
    }

    private String determineTrend(JsonNode history) {
        if (!history.isArray() || history.size() < 2) {
            return "observing";
        }
        JsonNode last = history.get(history.size() - 1);
        JsonNode previous = history.get(history.size() - 2);
        int lastGrade = last.path("grade").asInt(0);
        int previousGrade = previous.path("grade").asInt(0);
        if (lastGrade > previousGrade) {
            return "worsening";
        }
        if (lastGrade < previousGrade) {
            return "improving";
        }
        return "stable";
    }

    private String getLatestGrade(JsonNode defect) {
        JsonNode history = defect.path("history");
        if (history.isArray() && history.size() > 0) {
            return String.valueOf(history.get(history.size() - 1).path("grade").asInt());
        }
        return "unknown";
    }
}
