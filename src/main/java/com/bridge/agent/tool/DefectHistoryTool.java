package com.bridge.agent.tool;

import com.bridge.agent.entity.DefectRecord;
import com.bridge.agent.repository.DefectRecordRepository;
import com.bridge.agent.util.JsonUtil;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 工具：查询病害历史记录
 *
 * <p>工具名：search_defect_history
 * <p>输入：{"bridgeId": "BRG-001", "months": 36}
 * <p>输出：按时间倒序的病害记录文本
 *
 * <p>缓存：TTL 1h（检测期间可能有新记录写入）
 */
@Service
public class DefectHistoryTool {

    private final DefectRecordRepository repo;

    public DefectHistoryTool(DefectRecordRepository repo) {
        this.repo = repo;
    }

    @Cacheable(value = "defect-history", key = "#jsonInput")
    public String execute(String jsonInput) {
        String bridgeId = JsonUtil.getString(jsonInput, "bridgeId");
        Integer months = JsonUtil.getInt(jsonInput, "months");
        if (months == null) months = 36; // 默认近 3 年

        if (bridgeId == null || bridgeId.isBlank()) {
            return "错误：bridgeId 不能为空";
        }

        LocalDate since = LocalDate.now().minusMonths(months);
        List<DefectRecord> records = repo
                .findByBridgeIdAndInspectionDateAfterOrderByInspectionDateDesc(bridgeId, since);

        if (records.isEmpty()) {
            return String.format("桥梁 [%s] 近 %d 个月暂无病害记录", bridgeId, months);
        }

        // 返回所有记录的原始 JSON，供 DefectClusterTool 做语义聚类
        return formatRecords(records, bridgeId, months);
    }

    public String executeRaw(String bridgeId, int months) {
        LocalDate since = LocalDate.now().minusMonths(months);
        return repo.findByBridgeIdAndInspectionDateAfterOrderByInspectionDateDesc(bridgeId, since)
                .stream()
                .map(this::formatRecord)
                .collect(Collectors.joining("\n---\n"));
    }

    /** 返回适合语义聚类的原始描述列表 JSON */
    public List<String> getDescriptions(String bridgeId, int months) {
        LocalDate since = LocalDate.now().minusMonths(months);
        return repo.findByBridgeIdAndInspectionDateAfterOrderByInspectionDateDesc(bridgeId, since)
                .stream()
                .map(r -> r.getDescription() != null ? r.getDescription() : r.getRawDescription())
                .filter(d -> d != null && !d.isBlank())
                .collect(Collectors.toList());
    }

    /** 返回完整实体列表供聚类使用 */
    public List<DefectRecord> getRecords(String bridgeId, int months) {
        LocalDate since = LocalDate.now().minusMonths(months);
        return repo.findByBridgeIdAndInspectionDateAfterOrderByInspectionDateDesc(bridgeId, since);
    }

    private String formatRecords(List<DefectRecord> records, String bridgeId, int months) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("桥梁 [%s] 近 %d 个月共 %d 条病害记录：\n\n",
                bridgeId, months, records.size()));
        for (int i = 0; i < records.size(); i++) {
            sb.append(String.format("[%d] %s\n", i + 1, formatRecord(records.get(i))));
        }
        return sb.toString();
    }

    private String formatRecord(DefectRecord r) {
        return String.format("日期：%s | 构件：%s | 类型：%s | 等级：%s类 | %s",
                r.getInspectionDate(), r.getComponent(), r.getDefectType(),
                r.getGrade(), r.getDescription() != null ? r.getDescription() : r.getRawDescription());
    }
}
