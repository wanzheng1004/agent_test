package com.bridge.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具注册表 —— 统一管理所有 Agent 可调用的工具
 *
 * <p>设计要点：
 * <ul>
 *   <li>工具与框架解耦：不使用 Spring AI 的 @Tool 注解，而是手动注册</li>
 *   <li>每个 Agent 只开放 allowedTools 子集，减少 LLM 选错工具的概率</li>
 *   <li>execute() 统一入口，便于在此处添加限流、监控、审计</li>
 * </ul>
 *
 * <p>面试要点：
 * "每个 Agent 只挂载自己需要的工具子集。检测中 Agent 只有 2 个工具，
 *  工具越少 LLM 选错的概率越低，这是提升 Action 准确率的关键手段。"
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** 工具执行函数接口，接收 JSON 字符串输入，返回字符串结果 */
    @FunctionalInterface
    public interface ToolExecutor {
        String execute(String jsonInput) throws Exception;
    }

    /** 工具定义：名称、描述、输入格式说明、执行函数 */
    public record ToolDefinition(
            String name,
            String description,
            String inputSchema,   // JSON 示例，告知 LLM 参数格式
            ToolExecutor executor
    ) {}

    // 有序 Map，保证工具描述渲染顺序一致
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    /**
     * 注册工具（由 ToolInitializer 在应用启动时调用）
     */
    public void register(String name, String description,
                          String inputSchema, ToolExecutor executor) {
        tools.put(name, new ToolDefinition(name, description, inputSchema, executor));
        log.info("Tool registered: {}", name);
    }

    /**
     * 执行工具调用
     *
     * @param toolName  工具名（来自 LLM 的 Action 字段）
     * @param jsonInput 工具入参 JSON（来自 LLM 的 ActionInput 字段）
     * @return 工具执行结果字符串
     * @throws InvalidActionException 工具不存在时
     */
    public String execute(String toolName, String jsonInput) {
        ToolDefinition def = tools.get(toolName);
        if (def == null) {
            String available = String.join(", ", tools.keySet());
            throw new InvalidActionException(
                    "未知工具 '" + toolName + "'，可用工具：" + available);
        }
        try {
            long start = System.currentTimeMillis();
            String result = def.executor().execute(jsonInput);
            log.debug("Tool {} executed in {}ms", toolName,
                    System.currentTimeMillis() - start);
            return result;
        } catch (InvalidActionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Tool {} execution error: {}", toolName, e.getMessage());
            throw new RuntimeException("工具 [" + toolName + "] 执行异常：" + e.getMessage());
        }
    }

    /**
     * 为指定工具子集生成 LLM 可读的工具描述文本，注入 ReAct Prompt。
     *
     * @param allowedTools 本 Agent 允许使用的工具名集合
     */
    public String renderDescriptions(Set<String> allowedTools) {
        return tools.entrySet().stream()
                .filter(e -> allowedTools.contains(e.getKey()))
                .map(e -> formatTool(e.getValue()))
                .collect(Collectors.joining("\n\n"));
    }

    /** 验证工具名是否合法 */
    public boolean exists(String toolName) {
        return tools.containsKey(toolName);
    }

    private String formatTool(ToolDefinition tool) {
        return String.format(
                "【%s】\n描述：%s\n输入格式（JSON）：%s",
                tool.name(), tool.description(), tool.inputSchema());
    }
}
