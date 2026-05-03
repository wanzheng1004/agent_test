package com.bridge.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * ReAct 执行引擎 —— 显式实现 Thought-Action-Observation 循环
 *
 * <p>核心设计：不依赖 Spring AI 的自动 function calling，
 * 手写 for 循环，每轮：
 * <ol>
 *   <li>THINK  — 调 LLM，输出 Thought/Action/ActionInput 文本</li>
 *   <li>VALIDATE — 校验输出质量，质量差则 self-correct（最多一次）</li>
 *   <li>ACT    — 根据 Action 调用 ToolRegistry 执行工具</li>
 *   <li>OBSERVE — 将工具返回结果记入 AgentContext</li>
 *   <li>CHECK  — 检查终止条件（FINISH / ASK_USER / 错误超限 / 达最大次数）</li>
 * </ol>
 *
 * <p>面试常见问题：
 * <ul>
 *   <li>最大循环次数：{@code maxIterations}，默认 8，可配置</li>
 *   <li>无限循环保护：连续错误 {@code maxConsecutiveErrors} 次强制终止</li>
 *   <li>Thought 质量差：self-correction 机制，给一次重新推理机会</li>
 *   <li>工具选错：InvalidActionException 转为 INVALID_ACTION 观察，
 *       并在 Observation 中注入可用工具列表，引导 LLM 自行纠正</li>
 * </ul>
 */
@Component
public class ReActEngine {

    private static final Logger log = LoggerFactory.getLogger(ReActEngine.class);

    private final ChatClient chatClient;
    private final ToolRegistry toolRegistry;
    private final ThinkResultParser parser;

    @Value("${bridge.agent.react.max-iterations:8}")
    private int maxIterations;

    @Value("${bridge.agent.react.max-consecutive-errors:2}")
    private int maxConsecutiveErrors;

    public ReActEngine(ChatClient.Builder chatClientBuilder,
                       ToolRegistry toolRegistry,
                       ThinkResultParser parser) {
        this.chatClient = chatClientBuilder.build();
        this.toolRegistry = toolRegistry;
        this.parser = parser;
    }

    /**
     * 执行 ReAct 主循环
     *
     * @param userInput    用户当前输入
     * @param history      会话历史消息（用于上下文）
     * @param allowedTools 本 Agent 允许使用的工具子集
     * @param systemPrompt 本 Agent 的系统提示（含角色、行动规则）
     * @param sessionId    会话 ID
     * @param agentName    Agent 名称（用于日志）
     * @return 填充了完整执行轨迹和最终答案的 AgentContext
     */
    public AgentContext execute(String userInput,
                                 List<Message> history,
                                 Set<String> allowedTools,
                                 String systemPrompt,
                                 String sessionId,
                                 String agentName) {

        AgentContext ctx = new AgentContext(sessionId, agentName);
        String toolDescriptions = toolRegistry.renderDescriptions(allowedTools);

        log.info("[{}] ReAct started, allowedTools={}", agentName, allowedTools);

        // ======================================================
        // 主循环：Thought → Action → Observation
        // ======================================================
        for (int i = 0; i < maxIterations && ctx.isRunning(); i++) {

            log.debug("[{}] ReAct iteration {}/{}", agentName, i + 1, maxIterations);

            // =========================================
            // Step 1: THINK — 让 LLM 推理并选择动作
            // =========================================
            String llmOutput = think(ctx, userInput, history, toolDescriptions, systemPrompt);
            ThinkResult thought = parser.parse(llmOutput);

            // =========================================
            // Step 2: VALIDATE — 校验 Thought 质量
            //   质量差：action 为 null 或为空白
            // =========================================
            if (!thought.isValid()) {
                log.warn("[{}] Low quality thought at step {}, attempting self-correction",
                        agentName, i);
                thought = selfCorrect(ctx, llmOutput, thought, allowedTools, toolDescriptions, systemPrompt);

                if (!thought.isValid()) {
                    // 二次纠正仍失败 → 记录低质量步骤，继续（或触发连续错误保护）
                    AgentStep errorStep = new AgentStep(
                            i, thought.thought(), "INVALID", thought.actionInput(),
                            "输出格式无效，已尝试纠正", 0, StepStatus.THOUGHT_LOW_QUALITY);
                    ctx.addStep(errorStep);
                    if (ctx.consecutiveErrorCount() >= maxConsecutiveErrors) {
                        terminateWithFallback(ctx, AgentState.FAILED,
                                TerminationReason.CONSECUTIVE_ERRORS, userInput);
                    }
                    continue;
                }
            }

            // =========================================
            // Step 3: 检查终止动作
            // =========================================
            if ("FINISH".equals(thought.action())) {
                ctx.setFinalAnswer(thought.actionInput());
                ctx.terminate(AgentState.FINISHED, TerminationReason.NORMAL_FINISH);
                log.info("[{}] ReAct finished normally at step {}", agentName, i + 1);
                break;
            }

            if ("ASK_USER".equals(thought.action())) {
                ctx.setFinalAnswer(thought.actionInput()); // 追问内容作为"答案"返回
                ctx.terminate(AgentState.FINISHED, TerminationReason.WAITING_USER_INPUT);
                log.info("[{}] ReAct waiting for user input at step {}", agentName, i + 1);
                break;
            }

            // =========================================
            // Step 4: ACT — 调用工具
            // =========================================
            long actionStart = System.currentTimeMillis();
            String observation;
            StepStatus status;

            try {
                // 校验工具名是否合法（提前校验，提供更友好的错误信息）
                if (!toolRegistry.exists(thought.action())) {
                    throw new InvalidActionException(
                            "工具 '" + thought.action() + "' 不存在。" +
                            "当前场景可用工具：" + String.join(", ", allowedTools));
                }
                observation = toolRegistry.execute(thought.action(), thought.actionInput());
                status = StepStatus.SUCCESS;
            } catch (InvalidActionException e) {
                // 工具名不存在：将错误和可用工具列表作为 Observation，让 LLM 自行纠正
                observation = "⚠️ " + e.getMessage();
                status = StepStatus.INVALID_ACTION;
                log.warn("[{}] Invalid action at step {}: {}", agentName, i, e.getMessage());
            } catch (Exception e) {
                // 工具执行异常：告知 LLM 出了什么问题
                observation = "⚠️ 工具执行异常：" + e.getMessage();
                status = StepStatus.TOOL_ERROR;
                log.error("[{}] Tool error at step {}: {}", agentName, i, e.getMessage());
            }

            long latency = System.currentTimeMillis() - actionStart;

            // =========================================
            // Step 5: OBSERVE — 记录本步完整轨迹
            // =========================================
            AgentStep step = new AgentStep(
                    i, thought.thought(), thought.action(),
                    thought.actionInput(), observation, latency, status);
            ctx.addStep(step);

            // =========================================
            // Step 6: 连续错误保护
            // =========================================
            if (ctx.consecutiveErrorCount() >= maxConsecutiveErrors) {
                log.warn("[{}] Consecutive error limit reached, terminating", agentName);
                terminateWithFallback(ctx, AgentState.FAILED,
                        TerminationReason.CONSECUTIVE_ERRORS, userInput);
            }
        }

        // 主循环结束仍未终止 → 达到最大循环次数
        if (ctx.isRunning()) {
            log.warn("[{}] Max iterations ({}) reached", agentName, maxIterations);
            terminateWithFallback(ctx, AgentState.MAX_ITER_EXCEEDED,
                    TerminationReason.MAX_ITERATIONS, userInput);
        }

        log.info("[{}] ReAct completed: state={}, steps={}, terminationReason={}",
                agentName, ctx.getState(), ctx.getSteps().size(), ctx.getTerminationReason());
        return ctx;
    }

    // =====================================================
    // THINK 阶段：构造 ReAct Prompt，调用 LLM
    // =====================================================

    /**
     * 构造 ReAct Prompt 并调用 LLM，返回原始文本输出。
     *
     * <p>Prompt 结构：系统提示（角色+工具+格式要求）+ 轨迹 + 用户问题
     */
    private String think(AgentContext ctx, String userInput,
                          List<Message> history, String toolDescriptions,
                          String systemPrompt) {
        // 将轨迹和当前问题拼入 user message
        String userMessage = buildUserMessage(ctx.getTrajectoryText(), userInput);

        return chatClient.prompt()
                .system(systemPrompt
                        .replace("{toolDescriptions}", toolDescriptions)
                        .replace("{trajectory}", ctx.getTrajectoryText())
                        .replace("{userInput}", userInput))
                .messages(history)
                .user(userMessage)
                .call()
                .content();
    }

    // =====================================================
    // SELF-CORRECTION：输出质量差时的二次推理机会
    // =====================================================

    /**
     * 自我纠正机制。
     *
     * <p>面试要点：
     * "如果 LLM 第一次推理输出格式不对，我们不是直接放弃，
     *  而是把具体错误原因告诉 LLM，给一次重新推理的机会。
     *  只给一次机会，避免无限套娃。"
     */
    private ThinkResult selfCorrect(AgentContext ctx, String badOutput, ThinkResult badResult,
                                     Set<String> allowedTools, String toolDescriptions,
                                     String systemPrompt) {
        String problemDesc = parser.diagnoseProblem(badResult, badOutput);
        log.debug("[{}] Self-correcting: {}", ctx.getAgentName(), problemDesc);

        String correctionPrompt = String.format("""
                你之前的输出存在格式问题：%s

                请重新推理，严格按照以下格式输出（不要有任何其他文字）：
                Thought: <你的推理过程>
                Action: <工具名 或 FINISH 或 ASK_USER>
                ActionInput: <工具 JSON 参数 或 最终答案>

                可用工具：%s
                """, problemDesc, String.join(", ", allowedTools));

        String corrected = chatClient.prompt()
                .system(correctionPrompt)
                .user("基于已有轨迹：\n" + ctx.getTrajectoryText())
                .call()
                .content();

        return parser.parse(corrected);
    }

    // =====================================================
    // 兜底回答：异常终止时仍给用户有意义的回复
    // =====================================================

    /**
     * 从已成功收集的 Observation 中提炼有用信息，避免异常终止时返回空白错误。
     *
     * <p>面试要点：
     * "Agent 异常终止不等于什么都没做。如果已经执行了几步工具调用，
     *  会把已收集的部分结果提交给 LLM 生成一个尽力而为的回答。"
     */
    private void terminateWithFallback(AgentContext ctx, AgentState state,
                                        TerminationReason reason, String userInput) {
        String collectedInfo = ctx.getSteps().stream()
                .filter(s -> s.status() == StepStatus.SUCCESS)
                .map(s -> "【" + s.action() + "】\n" + s.observation())
                .reduce("", (a, b) -> a + "\n\n" + b);

        String fallback;
        if (collectedInfo.isBlank()) {
            fallback = "处理过程中遇到异常，未能完成请求。请尝试换一种描述方式，或联系管理员。";
        } else {
            fallback = chatClient.prompt()
                    .system("根据已收集到的部分信息，尽力回答用户问题。" +
                            "如有未完成的部分，诚实说明，不要编造数据。")
                    .user("用户问题：" + userInput + "\n\n已收集信息：\n" + collectedInfo)
                    .call()
                    .content();
        }

        ctx.setFinalAnswer(fallback);
        ctx.terminate(state, reason);
    }

    private String buildUserMessage(String trajectory, String userInput) {
        if (trajectory.contains("（尚无历史步骤")) {
            return "用户问题：" + userInput + "\n\n请开始推理：";
        }
        return "用户问题：" + userInput + "\n\n已执行轨迹：\n" + trajectory + "\n请继续推理：";
    }
}
