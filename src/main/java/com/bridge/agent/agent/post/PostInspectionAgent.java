package com.bridge.agent.agent.post;

import com.bridge.agent.core.plan.PlanExecuteContext;
import com.bridge.agent.core.plan.PlanExecuteEngine;
import com.bridge.agent.memory.BridgeMemoryService;
import com.bridge.agent.memory.SessionMemoryStore;
import com.bridge.agent.memory.dto.NormalizedDefect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 检修后 Agent —— 基于 Plan & Execute 模式
 *
 * <p>任务链固定：汇总会话病害 → 检索处置案例 + 查桥梁信息（并行）→ LLM 生成处置建议
 * 与检修前 Agent 相同，任务路径固定，选用 Plan & Execute。
 *
 * <p>完成后触发桥梁级记忆归档（将本次检测结果写入 MySQL bridge_memory）。
 */
@Service
public class PostInspectionAgent {

    private static final Logger log = LoggerFactory.getLogger(PostInspectionAgent.class);

    private final PlanExecuteEngine engine;
    private final SessionMemoryStore sessionStore;
    private final BridgeMemoryService bridgeMemoryService;
    private final String plannerPrompt;
    private final String synthesizerPrompt;

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "query_bridge_profile",
            "search_repair_cases"
    );

    public PostInspectionAgent(PlanExecuteEngine engine,
                                 SessionMemoryStore sessionStore,
                                 BridgeMemoryService bridgeMemoryService) {
        this.engine = engine;
        this.sessionStore = sessionStore;
        this.bridgeMemoryService = bridgeMemoryService;
        this.plannerPrompt = loadPrompt("post-inspection-planner.st");
        this.synthesizerPrompt = loadPrompt("post-inspection-synthesizer.st");
    }

    /**
     * 执行检修后汇总分析
     *
     * @param bridgeId  桥梁编号
     * @param sessionId 本次检测会话 ID
     * @return 含处置建议报告的执行上下文
     */
    public PlanExecuteContext execute(String bridgeId, String sessionId) {
        log.info("PostInspectionAgent started: bridgeId={}, sessionId={}", bridgeId, sessionId);

        // 读取本次会话病害清单，注入到 userInput 中
        List<NormalizedDefect> defects = sessionStore.getDefects(sessionId);
        String defectSummary = formatDefectList(defects);

        String userInput = String.format(
                "为桥梁 %s 的本次检测（会话 %s）生成处置建议报告。\n\n本次记录的病害清单：\n%s",
                bridgeId, sessionId, defectSummary);

        PlanExecuteContext ctx = engine.execute(
                userInput, plannerPrompt, synthesizerPrompt,
                ALLOWED_TOOLS, "PostInspectionAgent");

        // 归档本次检测到桥梁级记忆
        try {
            bridgeMemoryService.archiveSession(sessionId, bridgeId);
            log.info("Session archived to bridge memory: bridgeId={}, sessionId={}",
                    bridgeId, sessionId);
        } catch (Exception e) {
            log.error("Failed to archive session: {}", e.getMessage());
        }

        return ctx;
    }

    private String formatDefectList(List<NormalizedDefect> defects) {
        if (defects.isEmpty()) return "（本次会话暂无规范化病害记录）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < defects.size(); i++) {
            NormalizedDefect d = defects.get(i);
            sb.append(String.format("[%d] 构件：%s | 类型：%s | 等级：%s类 | 紧迫度：%s\n    %s\n",
                    i + 1, d.getComponent(), d.getDefectType(), d.getGrade(),
                    d.getUrgency(), d.getDescription()));
        }
        return sb.toString();
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
