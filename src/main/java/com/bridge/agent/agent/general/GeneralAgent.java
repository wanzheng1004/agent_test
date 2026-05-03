package com.bridge.agent.agent.general;

import com.bridge.agent.core.AgentContext;
import com.bridge.agent.core.ReActEngine;
import com.bridge.agent.memory.SessionMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 通用问答 Agent —— 基于 ReAct 模式
 *
 * <p>覆盖 10% 的非核心场景：
 * <ul>
 *   <li>病害历史追溯（某病害近年有无扩展）</li>
 *   <li>规范条款查询（JTG 条文是什么意思）</li>
 *   <li>临时性知识咨询</li>
 * </ul>
 *
 * <p>可调用工具范围最广（3 个），但由于场景开放，
 * 选择 ReAct 而非固定 Plan，支持多轮灵活推理。
 */
@Service
public class GeneralAgent {

    private static final Logger log = LoggerFactory.getLogger(GeneralAgent.class);

    private final ReActEngine reActEngine;
    private final SessionMemoryStore sessionStore;
    private final String systemPrompt;

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "retrieve_standard",
            "search_defect_history",
            "query_bridge_profile"
    );

    public GeneralAgent(ReActEngine reActEngine, SessionMemoryStore sessionStore) {
        this.reActEngine = reActEngine;
        this.sessionStore = sessionStore;
        this.systemPrompt = loadPrompt("react-general.st");
    }

    public AgentContext chat(String userMessage, String sessionId) {
        List<Message> history = sessionStore.getHistory(sessionId);
        return reActEngine.execute(
                userMessage, history, ALLOWED_TOOLS, systemPrompt,
                sessionId, "GeneralAgent");
    }

    private String loadPrompt(String filename) {
        try {
            return new ClassPathResource("prompts/" + filename)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load prompt: {}", filename);
            return "你是桥梁检测助手，帮助用户查询桥梁相关信息。";
        }
    }
}
