package com.bridge.agent.tool;

import com.bridge.agent.entity.RepairRecord;
import com.bridge.agent.repository.RepairRecordRepository;
import com.bridge.agent.util.JsonUtil;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工具：查询维修加固记录
 *
 * <p>工具名：query_repair_records
 * <p>输入：{"bridgeId": "BRG-001"}
 * <p>输出：维修记录文本列表
 *
 * <p>缓存：TTL 24h（维修记录更新频率低）
 */
@Service
public class RepairRecordTool {

    private final RepairRecordRepository repo;

    public RepairRecordTool(RepairRecordRepository repo) {
        this.repo = repo;
    }

    @Cacheable(value = "repair-records", key = "#jsonInput")
    public String execute(String jsonInput) {
        String bridgeId = JsonUtil.getString(jsonInput, "bridgeId");
        if (bridgeId == null || bridgeId.isBlank()) {
            return "错误：bridgeId 不能为空";
        }

        List<RepairRecord> records = repo.findByBridgeIdOrderByRepairDateDesc(bridgeId);
        if (records.isEmpty()) {
            return "桥梁 [" + bridgeId + "] 暂无维修加固记录";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("桥梁 [%s] 共 %d 条维修记录：\n\n", bridgeId, records.size()));
        for (int i = 0; i < records.size(); i++) {
            RepairRecord r = records.get(i);
            sb.append(String.format(
                    "[%d] %s | %s | 构件：%s\n    工艺：%s\n    施工单位：%s\n    费用：%s元 | 状态：%s\n\n",
                    i + 1, r.getRepairDate(), r.getRepairType(), r.getComponent(),
                    r.getRepairMethod() != null ? r.getRepairMethod() : r.getDescription(),
                    r.getContractor(),
                    r.getCost() != null ? r.getCost().toPlainString() : "未知",
                    r.getResult()));
        }
        return sb.toString();
    }
}
