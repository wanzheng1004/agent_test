package com.bridge.agent.orchestrator;

import com.bridge.agent.orchestrator.dto.IntentResult;
import com.bridge.agent.orchestrator.dto.SceneType;
import com.bridge.agent.orchestrator.dto.SlotCheckResult;
import org.springframework.stereotype.Component;

/**
 * 槽位校验器 —— 纯 Java 逻辑，无 LLM 调用
 *
 * <p>面试要点：
 * "槽位校验是确定性逻辑，不需要 LLM。
 *  每个场景的必要槽位是固定的，用 if-else 判断就够了。
 *  把确定性工作放在代码里，不浪费 LLM 算力做规则判断。"
 */
@Component
public class SlotValidator {

    /**
     * 按场景校验必要槽位
     *
     * <p>槽位规则：
     * <ul>
     *   <li>PRE_INSPECTION：必须有 bridgeId</li>
     *   <li>DURING_INSPECTION：必须有 bridgeId 和 defectDescription</li>
     *   <li>POST_INSPECTION：必须有 bridgeId（sessionId 从 Redis 获取，无需用户提供）</li>
     *   <li>GENERAL：无必要槽位，直接执行</li>
     * </ul>
     */
    public SlotCheckResult validate(IntentResult intent) {
        return switch (intent.scene()) {
            case PRE_INSPECTION -> {
                if (isBlank(intent.bridgeId())) {
                    yield SlotCheckResult.missing("bridgeId",
                            "请问您要检测的是哪座桥？请提供桥梁编号（如 BRG-001）或桥梁名称。");
                }
                yield SlotCheckResult.ok();
            }

            case DURING_INSPECTION -> {
                if (isBlank(intent.bridgeId())) {
                    yield SlotCheckResult.missing("bridgeId",
                            "请问您正在检测哪座桥？请提供桥梁编号。");
                }
                if (isBlank(intent.defectDescription())) {
                    yield SlotCheckResult.missing("defectDescription",
                            "请描述您发现的病害情况（例如：位置、类型、大概尺寸）。");
                }
                yield SlotCheckResult.ok();
            }

            case POST_INSPECTION -> {
                if (isBlank(intent.bridgeId())) {
                    yield SlotCheckResult.missing("bridgeId",
                            "请问本次检测的是哪座桥？请提供桥梁编号。");
                }
                yield SlotCheckResult.ok();
            }

            case GENERAL ->
                // 通用问答无必要槽位，直接执行
                SlotCheckResult.ok();
        };
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
