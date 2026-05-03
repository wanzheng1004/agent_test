package com.bridge.agent.tool;

import com.bridge.agent.entity.BridgeProfile;
import com.bridge.agent.repository.BridgeProfileRepository;
import com.bridge.agent.util.JsonUtil;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工具：查询桥梁基础档案
 *
 * <p>工具名：query_bridge_profile
 * <p>输入：{"bridgeId": "BRG-001"}
 * <p>输出：桥梁档案 JSON 文本
 *
 * <p>缓存：TTL 24h（档案变动频率极低）
 */
@Service
public class BridgeProfileTool {

    private final BridgeProfileRepository repo;

    public BridgeProfileTool(BridgeProfileRepository repo) {
        this.repo = repo;
    }

    /**
     * 查询桥梁档案
     *
     * @param jsonInput {"bridgeId": "BRG-001"}
     * @return 桥梁档案 JSON 字符串
     */
    @Cacheable(value = "bridge-profile", key = "#jsonInput")
    public String execute(String jsonInput) {
        String bridgeId = JsonUtil.getString(jsonInput, "bridgeId");
        if (bridgeId == null || bridgeId.isBlank()) {
            return "错误：bridgeId 不能为空";
        }

        // 先按 ID 精确查找，查不到再按名称模糊查找
        BridgeProfile profile = repo.findById(bridgeId).orElse(null);
        if (profile == null) {
            List<BridgeProfile> byName = repo.findByNameContaining(bridgeId);
            if (!byName.isEmpty()) {
                profile = byName.get(0);
            }
        }

        if (profile == null) {
            return "未找到桥梁 [" + bridgeId + "] 的档案信息";
        }

        return buildProfileText(profile);
    }

    private String buildProfileText(BridgeProfile p) {
        return String.format("""
                桥梁编号：%s
                桥梁名称：%s
                桥梁类型：%s
                建造年份：%d年（已使用 %d 年）
                设计使用年限：%d年（剩余约 %d 年）
                跨径组合：%s
                荷载等级：%s
                公路等级：%s
                桥面宽度：%.1fm
                桥梁全长：%.1fm
                位置：%s
                管养单位：%s
                责任工程师：%s
                """,
                p.getBridgeId(), p.getName(), p.getBridgeType(),
                p.getBuildYear(), p.getUsedYears(),
                p.getDesignLife(), p.getRemainingLife(),
                p.getSpanDesc(), p.getLoadGrade(), p.getRoadGrade(),
                p.getWidth() != null ? p.getWidth() : 0.0,
                p.getTotalLength() != null ? p.getTotalLength() : 0.0,
                p.getLocationDesc(), p.getAdminUnit(), p.getInspector());
    }
}
