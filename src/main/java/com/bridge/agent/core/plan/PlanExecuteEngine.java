package com.bridge.agent.core.plan;

import com.bridge.agent.core.InvalidActionException;
import com.bridge.agent.core.StepStatus;
import com.bridge.agent.core.ToolRegistry;
import com.bridge.agent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Plan & Execute 执行引擎 —— 三阶段显式实现
 *
 * <p>三个阶段（均有明确代码边界，面试时可指着代码讲）：
 * <ol>
 *   <li>PLAN     — LLM 调用一次，生成 JSON 格式执行计划</li>
 *   <li>EXECUTE  — 按计划执行，并行阶段用 CompletableFuture.allOf()</li>
 *   <li>SYNTHESIZE — LLM 调用一次，综合所有工具结果生成最终输出</li>
 * </ol>
 *
 * <p>与 ReAct 的核心区别（面试必问）：
 * <ul>
 *   <li>P&E：先规划再执行，LLM 只调用 2 次（Plan + Synthesize）</li>
 *   <li>ReAct：每步都回 LLM，LLM 调用 N 次</li>
 *   <li>P&E 更快更稳定，但中途无法动态改变路径</li>
 *   <li>所以任务路径固定（检修前/后）用 P&E，路径动态（检测中）用 ReAct</li>
 * </ul>
 *
 * <p>Replan 机制：
 * <ul>
 *   <li>每个阶段执行完后可配置后置条件检查</li>
 *   <li>条件不满足时，带错误原因重新调用 LLM 生成新计划</li>
 *   <li>最多 Replan 1 次，避免无限循环</li>
 * </ul>
 */
@Component
public class PlanExecuteEngine {

    private static final Logger log = LoggerFactory.getLogger(PlanExecuteEngine.class);
    private static final int MAX_REPLAN = 1; // 最多重新规划一次

    private final ChatClient chatClient;
    private final ToolRegistry toolRegistry;

    public PlanExecuteEngine(ChatClient.Builder chatClientBuilder,
                              ToolRegistry toolRegistry) {
        this.chatClient = chatClientBuilder.build();
        this.toolRegistry = toolRegistry;
    }

    /**
     * 执行完整的 Plan & Execute 流程
     *
     * @param userInput         用户请求
     * @param plannerPrompt     PLAN 阶段 LLM 提示（含工具描述）
     * @param synthesizerPrompt SYNTHESIZE 阶段 LLM 提示
     * @param allowedTools      本 Agent 允许使用的工具子集
     * @param agentName         Agent 名称（用于日志）
     */
    public PlanExecuteContext execute(String userInput,
                                       String plannerPrompt,
                                       String synthesizerPrompt,
                                       Set<String> allowedTools,
                                       String agentName) {
        PlanExecuteContext ctx = new PlanExecuteContext(userInput);
        int replanCount = 0;

        log.info("[{}] Plan&Execute started", agentName);

        // ============================================================
        // Phase 1: PLAN — LLM 生成结构化执行计划
        // ============================================================
        ExecutionPlan plan = generatePlan(userInput, plannerPrompt, allowedTools, null);
        ctx.setPlan(plan);
        log.info("[{}] Plan generated: {} phases", agentName, plan.phases().size());

        // 计划校验：检查所有 tool 在 ToolRegistry 中存在
        List<String> invalidTools = validatePlan(plan, allowedTools);
        if (!invalidTools.isEmpty()) {
            String errorMsg = "计划包含无效工具：" + invalidTools;
            log.warn("[{}] Plan validation failed: {}", agentName, errorMsg);
            // 触发首次 Replan
            plan = generatePlan(userInput, plannerPrompt, allowedTools, errorMsg);
            ctx.setPlan(plan);
            ctx.markReplanned(errorMsg);
            replanCount++;
        }

        // ============================================================
        // Phase 2: EXECUTE — 按计划逐阶段执行
        // ============================================================
        for (PlanPhase phase : plan.phases()) {
            log.info("[{}] Executing phase: {}, parallel={}", agentName, phase.name(), phase.parallel());

            if (phase.parallel()) {
                executeParallelPhase(phase, ctx);
            } else {
                executeSequentialPhase(phase, ctx);
            }

            // 阶段后置条件检查 + Replan（最多一次）
            if (phase.hasPostCondition() && replanCount < MAX_REPLAN) {
                String conditionResult = evaluatePostCondition(phase.postCondition(), ctx);
                if (conditionResult != null) {
                    // 条件不满足，触发 Replan
                    log.warn("[{}] Phase post-condition failed: {}", agentName, conditionResult);
                    plan = generatePlan(userInput, plannerPrompt, allowedTools, conditionResult);
                    ctx.setPlan(plan);
                    ctx.markReplanned(conditionResult);
                    replanCount++;
                    // 注意：Replan 后重新从头执行新计划剩余阶段
                    break;
                }
            }
        }

        // ============================================================
        // Phase 3: SYNTHESIZE — LLM 综合所有工具结果生成最终输出
        // ============================================================
        String finalOutput = synthesize(ctx, synthesizerPrompt, userInput);
        ctx.setFinalOutput(finalOutput);

        log.info("[{}] Plan&Execute completed, stepResults={}, replanned={}",
                agentName, ctx.getStepResults().size(), ctx.isReplanned());
        return ctx;
    }

    // =====================================================================
    // PLAN 阶段
    // =====================================================================

    private ExecutionPlan generatePlan(String userInput, String plannerPrompt,
                                        Set<String> allowedTools, String replanReason) {
        String toolDescriptions = toolRegistry.renderDescriptions(allowedTools);
        String prompt = plannerPrompt.replace("{toolDescriptions}", toolDescriptions);

        String userMsg = replanReason == null
                ? "请为以下请求生成执行计划：\n" + userInput
                : "原计划执行遇到问题：" + replanReason + "\n请重新生成执行计划：\n" + userInput;

        String response = chatClient.prompt()
                .system(prompt)
                .user(userMsg)
                .call()
                .content();

        // 提取 JSON（LLM 可能在 JSON 前后有多余文字）
        String json = extractJson(response);
        return JsonUtil.parse(json, ExecutionPlan.class);
    }

    private List<String> validatePlan(ExecutionPlan plan, Set<String> allowedTools) {
        return plan.phases().stream()
                .flatMap(phase -> phase.steps().stream())
                .map(PlanStep::tool)
                .filter(tool -> !toolRegistry.exists(tool))
                .collect(Collectors.toList());
    }

    // =====================================================================
    // EXECUTE 阶段
    // =====================================================================

    /** 并行执行阶段 —— 使用 CompletableFuture.allOf() */
    private void executeParallelPhase(PlanPhase phase, PlanExecuteContext ctx) {
        List<CompletableFuture<StepResult>> futures = phase.steps().stream()
                .map(step -> CompletableFuture.supplyAsync(() -> executeSingleStep(step, ctx)))
                .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        futures.forEach(f -> ctx.addStepResult(f.join()));
    }

    /** 串行执行阶段 */
    private void executeSequentialPhase(PlanPhase phase, PlanExecuteContext ctx) {
        for (PlanStep step : phase.steps()) {
            ctx.addStepResult(executeSingleStep(step, ctx));
        }
    }

    /**
     * 执行单个步骤。
     *
     * <p>支持步骤间数据引用：步骤 input 中的 ${key} 会被替换为对应步骤的输出结果。
     * 例如：cluster_defects 的 input 可以引用 ${step_1b} 来获取前一步的病害列表。
     */
    private StepResult executeSingleStep(PlanStep step, PlanExecuteContext ctx) {
        long start = System.currentTimeMillis();
        try {
            // 解析步骤输入中的占位符（支持步骤间数据流）
            String rawInput = JsonUtil.toJson(step.input());
            String resolvedInput = ctx.resolveReference(rawInput);

            log.debug("Executing step: tool={}, description={}", step.tool(), step.description());
            String result = toolRegistry.execute(step.tool(), resolvedInput);

            return new StepResult(step, result, StepStatus.SUCCESS,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("Step execution failed: tool={}, error={}", step.tool(), e.getMessage());
            return new StepResult(step, "执行失败：" + e.getMessage(),
                    StepStatus.TOOL_ERROR, System.currentTimeMillis() - start);
        }
    }

    // =====================================================================
    // SYNTHESIZE 阶段
    // =====================================================================

    private String synthesize(PlanExecuteContext ctx, String synthesizerPrompt, String userInput) {
        String allResults = ctx.toSynthesisInput();
        boolean hasFailedSteps = ctx.getStepResults().stream()
                .anyMatch(r -> !r.isSuccess());

        String userMsg = String.format("""
                用户请求：%s

                执行结果汇总：
                %s
                %s
                请根据以上数据生成分析报告。
                """,
                userInput,
                allResults,
                hasFailedSteps ? "\n⚠️ 注意：部分步骤执行失败，相关数据可能不完整，请在报告中说明。" : "");

        return chatClient.prompt()
                .system(synthesizerPrompt)
                .user(userMsg)
                .call()
                .content();
    }

    // =====================================================================
    // 工具方法
    // =====================================================================

    /**
     * 后置条件检查（简单实现：让 LLM 判断结果是否满足条件）。
     * 返回 null 表示条件满足；返回错误描述表示不满足。
     */
    private String evaluatePostCondition(String condition, PlanExecuteContext ctx) {
        // 简化实现：只检查是否有严重失败（全部步骤都失败）
        long failCount = ctx.getStepResults().stream().filter(r -> !r.isSuccess()).count();
        if (failCount == ctx.getStepResults().size() && !ctx.getStepResults().isEmpty()) {
            return "所有步骤均执行失败，无法继续";
        }
        return null; // 条件满足
    }

    /** 从 LLM 输出中提取 JSON 部分（处理 ```json ... ``` 包裹的情况） */
    private String extractJson(String response) {
        if (response == null) return "{}";
        // 去掉 markdown 代码块
        String cleaned = response.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        // 找到第一个 { 到最后一个 } 之间的内容
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned;
    }
}
