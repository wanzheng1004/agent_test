package com.bridge.agent.agent.pre;

import com.bridge.agent.core.plan.PlanExecuteContext;
import com.bridge.agent.core.plan.PlanExecuteEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 检修前 Agent —— 基于 Plan & Execute 模式
 *
 * <p>任务链固定：查档案 + 查病害 + 查维修（并行）→ 语义聚类 → LLM 综合生成健康画像
 * 步骤间无动态分支，选用 Plan & Execute 效率高（只调用 2 次 LLM）。
 *
 * <p>面试对比点：
 * "检修前任务链固定，所以用 Plan & Execute。检修前不需要 ReAct 的动态决策，
 *  因为不管历史数据是什么，都需要查这三类数据，不会因为中间结果改变下一步要做什么。"
 */
@Service
public class PreInspectionAgent {

    private static final Logger log = LoggerFactory.getLogger(PreInspectionAgent.class);

    private final PlanExecuteEngine engine;
    private final String plannerPrompt;
    private final String synthesizerPrompt;

    /** 本 Agent 允许使用的工具子集（不暴露检测中/检修后工具，减少 LLM 规划错误） */
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "query_bridge_profile",
            "search_defect_history",
            "query_repair_records",
            "cluster_defects"
    );

    public PreInspectionAgent(PlanExecuteEngine engine) {
        this.engine = engine;
        this.plannerPrompt = loadPrompt("pre-inspection-planner.st");
        this.synthesizerPrompt = loadPrompt("pre-inspection-synthesizer.st");
    }

    /**
     * 执行检修前分析，生成桥梁健康画像
     *
     * @param bridgeId 桥梁编号
     * @return 包含执行轨迹和最终报告的上下文
     */
    public PlanExecuteContext execute(String bridgeId) {
        log.info("PreInspectionAgent started for bridgeId={}", bridgeId);

        String userInput = String.format("为桥梁 %s 生成检测前健康画像，" +
                "需要查询基础档案、近3年病害历史、维修记录，并分析高频病害", bridgeId);

        return engine.execute(
                userInput,
                plannerPrompt.replace("{toolDescriptions}", ""),  // engine 会自动填充
                synthesizerPrompt,
                ALLOWED_TOOLS,
                "PreInspectionAgent"
        );
    }

    private String loadPrompt(String filename) {
        try {
            return new ClassPathResource("prompts/" + filename)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load prompt: {}", filename);
            return "";
        }
    }
}
