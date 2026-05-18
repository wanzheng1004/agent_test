package com.bridge.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    @FunctionalInterface
    public interface ToolExecutor {
        String execute(String jsonInput) throws Exception;
    }

    public record ToolDefinition(ToolSpec spec, ToolExecutor executor) {
        public String name() {
            return spec.name();
        }
    }

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    public void register(String name, String description, String inputSchema, ToolExecutor executor) {
        register(ToolSpec.readOnly(name, description, inputSchema), executor);
    }

    public void register(ToolSpec spec, ToolExecutor executor) {
        if (spec == null || spec.name() == null || spec.name().isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        tools.put(spec.name(), new ToolDefinition(spec, executor));
        log.info("Tool registered: name={}, sensitivity={}, policy={}",
                spec.name(), spec.sensitivity(), spec.failurePolicy());
    }

    public String execute(String toolName, String jsonInput) {
        ToolDefinition def = tools.get(toolName);
        if (def == null) {
            throw new InvalidActionException("Unknown tool '" + toolName + "'. Available tools: "
                    + String.join(", ", tools.keySet()));
        }
        try {
            long start = System.currentTimeMillis();
            String result = def.executor().execute(jsonInput);
            log.debug("Tool {} executed in {}ms", toolName, System.currentTimeMillis() - start);
            return result;
        } catch (InvalidActionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Tool {} execution error: {}", toolName, e.getMessage());
            throw new RuntimeException("Tool [" + toolName + "] execution failed: " + e.getMessage(), e);
        }
    }

    public String renderDescriptions(Set<String> allowedTools) {
        return tools.entrySet().stream()
                .filter(e -> allowedTools == null || allowedTools.contains(e.getKey()))
                .map(e -> formatTool(e.getValue().spec()))
                .collect(Collectors.joining("\n\n"));
    }

    public boolean exists(String toolName) {
        return tools.containsKey(toolName);
    }

    public ToolSpec getSpec(String toolName) {
        ToolDefinition definition = tools.get(toolName);
        return definition == null ? null : definition.spec();
    }

    public Map<String, ToolDefinition> getTools() {
        return Collections.unmodifiableMap(tools);
    }

    private String formatTool(ToolSpec tool) {
        return String.format("""
                Tool: %s
                Description: %s
                Input JSON schema/example: %s
                Allowed scenes: %s
                Timeout: %dms
                Sensitivity: %s
                Failure policy: %s
                Requires approval: %s
                """,
                tool.name(),
                tool.description(),
                tool.inputSchema(),
                tool.allowedScenes().isEmpty() ? "ALL" : String.join(",", tool.allowedScenes()),
                tool.timeoutMs(),
                tool.sensitivity(),
                tool.failurePolicy(),
                tool.requiresApproval());
    }
}
