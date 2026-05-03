package com.bridge.agent.orchestrator;

import com.bridge.agent.agent.during.DuringInspectionAgent;
import com.bridge.agent.agent.general.GeneralAgent;
import com.bridge.agent.agent.post.PostInspectionAgent;
import com.bridge.agent.agent.pre.PreInspectionAgent;
import com.bridge.agent.core.AgentContext;
import com.bridge.agent.core.plan.PlanExecuteContext;
import com.bridge.agent.memory.ContextWindowManager;
import com.bridge.agent.memory.SessionMemoryStore;
import com.bridge.agent.memory.UserPreferenceService;
import com.bridge.agent.orchestrator.dto.ChatRequest;
import com.bridge.agent.orchestrator.dto.IntentResult;
import com.bridge.agent.orchestrator.dto.SlotCheckResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 编排器 —— 确定性路由（非 Agent 循环）
 *
 * <p>职责：
 * <ol>
 *   <li>意图分类（单次 LLM 调用）</li>
 *   <li>槽位校验（纯 Java 逻辑）</li>
 *   <li>Agent 路由（switch-case 确定性路由）</li>
 * </ol>
 *
 * <p>面试要点：
 * "编排器本身不是 Agent，不需要 LLM 循环推理。
 *  意图分类是一次 LLM 调用，槽位校验是 if-else，路由是 switch-case。
 *  把确定性工作放在代码里，不浪费 LLM 算力，也更可靠。
 *  只有当 LLM 的推理能力真正有价值时，才调 LLM。"
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final IntentClassifier intentClassifier;
    private final SlotValidator slotValidator;
    private final SessionMemoryStore sessionStore;
    private final ContextWindowManager contextManager;
    private final UserPreferenceService userPreferenceService;
    private final PreInspectionAgent preAgent;
    private final DuringInspectionAgent duringAgent;
    private final PostInspectionAgent postAgent;
    private final GeneralAgent generalAgent;

    public AgentOrchestrator(IntentClassifier intentClassifier,
                               SlotValidator slotValidator,
                               SessionMemoryStore sessionStore,
                               ContextWindowManager contextManager,
                               UserPreferenceService userPreferenceService,
                               PreInspectionAgent preAgent,
                               DuringInspectionAgent duringAgent,
                               PostInspectionAgent postAgent,
                               GeneralAgent generalAgent) {
        this.intentClassifier = intentClassifier;
        this.slotValidator = slotValidator;
        this.sessionStore = sessionStore;
        this.contextManager = contextManager;
        this.userPreferenceService = userPreferenceService;
        this.preAgent = preAgent;
        this.duringAgent = duringAgent;
        this.postAgent = postAgent;
        this.generalAgent = generalAgent;
    }

    /**
     * 处理用户请求，路由到对应 Agent
     *
     * @return Agent 执行结果的最终文本答案
     */
    public String chat(ChatRequest request) {
        String sessionId = request.sessionId();
        // userMessage 可能被术语映射修改，用非 final 局部变量
        String userMessage = request.message();

        log.info("Orchestrator received request: sessionId={}, messageLen={}",
                sessionId, userMessage.length());

        // ============================================================
        // Step 0: 用户级记忆 — 术语映射（纯 Java，不调 LLM）
        // 将用户惯用非标准术语替换为规范术语，提高意图分类准确率
        // ============================================================
        String userId = request.userId();
        if (userId != null && !userId.isBlank()) {
            userMessage = userPreferenceService.applyTerminologyMapping(userId, userMessage);
        }

        // ============================================================
        // Step 1: 意图分类（单次 LLM 调用，有结构化输出）
        // ============================================================
        List<Message> history = sessionStore.getHistory(sessionId);
        IntentResult intent = intentClassifier.classify(userMessage, history);

        // 将识别到的 bridgeId 存入会话元数据，供后续轮次复用
        if (intent.bridgeId() != null) {
            sessionStore.setMeta(sessionId, "bridgeId", intent.bridgeId());
            // 用户级记忆：将本次操作的桥梁加入常用列表（后台更新，不影响主流程）
            if (userId != null && !userId.isBlank()) {
                try {
                    userPreferenceService.addPreferredBridge(userId, intent.bridgeId());
                } catch (Exception e) {
                    log.debug("Failed to update preferred bridges: {}", e.getMessage());
                }
            }
        }
        // 如果本次没有识别到 bridgeId，尝试从会话元数据中读取（上轮已提供过）
        String bridgeId = intent.bridgeId() != null
                ? intent.bridgeId()
                : sessionStore.getMeta(sessionId, "bridgeId");

        // ============================================================
        // Step 2: 槽位校验（纯 Java 逻辑，不调 LLM）
        // ============================================================
        IntentResult enrichedIntent = bridgeId != null && intent.bridgeId() == null
                ? new IntentResult(intent.scene(), bridgeId,
                    intent.defectDescription(), intent.standardRef(), intent.missingSlots())
                : intent;

        SlotCheckResult slotCheck = slotValidator.validate(enrichedIntent);

        if (!slotCheck.complete()) {
            // 槽位不足：直接返回引导性追问，不进入任何 Agent
            log.info("Slot check failed: missing={}, asking user",
                    slotCheck.missingSlotKey());
            return slotCheck.guideQuestion();
        }

        // ============================================================
        // Step 3: 确定性路由（switch-case，不需要 LLM 决策）
        // ============================================================
        log.info("Routing to agent: scene={}, bridgeId={}", intent.scene(), bridgeId);

        return switch (intent.scene()) {

            case PRE_INSPECTION -> {
                PlanExecuteContext ctx = preAgent.execute(bridgeId);
                yield ctx.getFinalOutput();
            }

            case DURING_INSPECTION -> {
                AgentContext ctx = duringAgent.chat(userMessage, sessionId, bridgeId);
                yield ctx.getFinalAnswer();
            }

            case POST_INSPECTION -> {
                PlanExecuteContext ctx = postAgent.execute(bridgeId, sessionId);
                yield ctx.getFinalOutput();
            }

            case GENERAL -> {
                AgentContext ctx = generalAgent.chat(userMessage, sessionId);
                yield ctx.getFinalAnswer();
            }
        };
    }
}
