package com.bridge.agent.orchestrator;

import com.bridge.agent.agent.during.DuringInspectionAgent;
import com.bridge.agent.agent.general.GeneralAgent;
import com.bridge.agent.agent.post.PostInspectionAgent;
import com.bridge.agent.agent.pre.PreInspectionAgent;
import com.bridge.agent.core.AgentContext;
import com.bridge.agent.core.TerminationReason;
import com.bridge.agent.core.plan.PlanExecuteContext;
import com.bridge.agent.memory.ContextWindowManager;
import com.bridge.agent.memory.MemoryContextAssembler;
import com.bridge.agent.memory.SessionMemoryStore;
import com.bridge.agent.memory.UserPreferenceService;
import com.bridge.agent.orchestrator.dto.AgentChatResult;
import com.bridge.agent.orchestrator.dto.ChatRequest;
import com.bridge.agent.orchestrator.dto.IntentResult;
import com.bridge.agent.orchestrator.dto.SceneType;
import com.bridge.agent.orchestrator.dto.SlotCheckResult;
import com.bridge.agent.runtime.AgentEvent;
import com.bridge.agent.runtime.AgentRuntimeRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final IntentClassifier intentClassifier;
    private final SlotValidator slotValidator;
    private final SessionMemoryStore sessionStore;
    private final UserPreferenceService userPreferenceService;
    private final PreInspectionAgent preAgent;
    private final DuringInspectionAgent duringAgent;
    private final PostInspectionAgent postAgent;
    private final GeneralAgent generalAgent;
    private final AgentRuntimeRecorder runtimeRecorder;
    private final MemoryContextAssembler memoryContextAssembler;

    public AgentOrchestrator(IntentClassifier intentClassifier,
                             SlotValidator slotValidator,
                             SessionMemoryStore sessionStore,
                             ContextWindowManager contextManager,
                             UserPreferenceService userPreferenceService,
                             PreInspectionAgent preAgent,
                             DuringInspectionAgent duringAgent,
                             PostInspectionAgent postAgent,
                             GeneralAgent generalAgent,
                             AgentRuntimeRecorder runtimeRecorder,
                             MemoryContextAssembler memoryContextAssembler) {
        this.intentClassifier = intentClassifier;
        this.slotValidator = slotValidator;
        this.sessionStore = sessionStore;
        this.userPreferenceService = userPreferenceService;
        this.preAgent = preAgent;
        this.duringAgent = duringAgent;
        this.postAgent = postAgent;
        this.generalAgent = generalAgent;
        this.runtimeRecorder = runtimeRecorder;
        this.memoryContextAssembler = memoryContextAssembler;
    }

    public String chat(ChatRequest request) {
        return handle(request).answer();
    }

    public AgentChatResult handle(ChatRequest request) {
        String sessionId = request.sessionId();
        String userId = request.userId();
        String userMessage = request.message() == null ? "" : request.message();

        log.info("Orchestrator received request: sessionId={}, messageLen={}",
                sessionId, userMessage.length());

        if (userId != null && !userId.isBlank()) {
            userMessage = userPreferenceService.applyTerminologyMapping(userId, userMessage);
        }

        List<Message> history = sessionStore.getHistory(sessionId);
        IntentResult intent = intentClassifier.classify(userMessage, history);

        if (intent.bridgeId() != null) {
            sessionStore.setMeta(sessionId, "bridgeId", intent.bridgeId());
            if (userId != null && !userId.isBlank()) {
                try {
                    userPreferenceService.addPreferredBridge(userId, intent.bridgeId());
                } catch (Exception e) {
                    log.debug("Failed to update preferred bridge list: {}", e.getMessage());
                }
            }
        }

        String bridgeId = intent.bridgeId() != null
                ? intent.bridgeId()
                : sessionStore.getMeta(sessionId, "bridgeId");

        IntentResult enrichedIntent = bridgeId != null && intent.bridgeId() == null
                ? new IntentResult(intent.scene(), bridgeId,
                intent.defectDescription(), intent.standardRef(), intent.missingSlots())
                : intent;

        SlotCheckResult slotCheck = slotValidator.validate(enrichedIntent);
        if (!slotCheck.complete()) {
            return new AgentChatResult(
                    sessionId,
                    slotCheck.guideQuestion(),
                    null,
                    enrichedIntent.scene(),
                    TerminationReason.WAITING_USER_INPUT,
                    List.of());
        }

        log.info("Routing to agent: scene={}, bridgeId={}", enrichedIntent.scene(), bridgeId);
        AgentChatResult result = switch (enrichedIntent.scene()) {
            case PRE_INSPECTION -> fromPlanContext(
                    sessionId,
                    preAgent.execute(bridgeId),
                    SceneType.PRE_INSPECTION);
            case DURING_INSPECTION -> fromAgentContext(
                    sessionId,
                    duringAgent.chat(userMessage, sessionId, bridgeId),
                    SceneType.DURING_INSPECTION);
            case POST_INSPECTION -> fromPlanContext(
                    sessionId,
                    postAgent.execute(bridgeId, sessionId),
                    SceneType.POST_INSPECTION);
            case GENERAL -> fromAgentContext(
                    sessionId,
                    generalAgent.chat(userMessage, sessionId),
                    SceneType.GENERAL);
        };
        memoryContextAssembler.assemble(
                result.runId(),
                sessionId,
                bridgeId,
                userId,
                result.scene());
        return new AgentChatResult(
                result.sessionId(),
                result.answer(),
                result.runId(),
                result.scene(),
                result.terminationReason(),
                events(result.runId()));
    }

    private AgentChatResult fromAgentContext(String sessionId,
                                             AgentContext ctx,
                                             SceneType scene) {
        return new AgentChatResult(
                sessionId,
                ctx.getFinalAnswer(),
                ctx.getRunId(),
                scene,
                ctx.getTerminationReason(),
                events(ctx.getRunId()));
    }

    private AgentChatResult fromPlanContext(String sessionId,
                                            PlanExecuteContext ctx,
                                            SceneType scene) {
        return new AgentChatResult(
                sessionId,
                ctx.getFinalOutput(),
                ctx.getRunId(),
                scene,
                TerminationReason.NORMAL_FINISH,
                events(ctx.getRunId()));
    }

    private List<AgentEvent> events(String runId) {
        return runId == null ? List.of() : runtimeRecorder.getEvents(runId);
    }
}
