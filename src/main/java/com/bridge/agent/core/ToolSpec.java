package com.bridge.agent.core;

import java.time.Duration;
import java.util.Set;

public record ToolSpec(
        String name,
        String description,
        String inputSchema,
        Set<String> allowedScenes,
        Duration timeout,
        ToolFailurePolicy failurePolicy,
        ToolSensitivity sensitivity,
        boolean requiresApproval,
        Duration cacheTtl
) {
    public static ToolSpec readOnly(String name, String description, String inputSchema) {
        return new ToolSpec(
                name,
                description,
                inputSchema,
                Set.of(),
                Duration.ofSeconds(20),
                ToolFailurePolicy.RETURN_OBSERVATION,
                ToolSensitivity.READ_ONLY,
                false,
                Duration.ZERO
        );
    }

    public static ToolSpec write(String name, String description, String inputSchema) {
        return readOnly(name, description, inputSchema)
                .withSensitivity(ToolSensitivity.WRITE)
                .withApproval(true);
    }

    public ToolSpec withAllowedScenes(String... scenes) {
        return new ToolSpec(name, description, inputSchema,
                scenes == null ? Set.of() : Set.of(scenes),
                timeout, failurePolicy, sensitivity, requiresApproval, cacheTtl);
    }

    public ToolSpec withTimeout(Duration newTimeout) {
        return new ToolSpec(name, description, inputSchema, allowedScenes,
                newTimeout == null ? Duration.ofSeconds(20) : newTimeout,
                failurePolicy, sensitivity, requiresApproval, cacheTtl);
    }

    public ToolSpec withFailurePolicy(ToolFailurePolicy newPolicy) {
        return new ToolSpec(name, description, inputSchema, allowedScenes, timeout,
                newPolicy == null ? ToolFailurePolicy.RETURN_OBSERVATION : newPolicy,
                sensitivity, requiresApproval, cacheTtl);
    }

    public ToolSpec withSensitivity(ToolSensitivity newSensitivity) {
        return new ToolSpec(name, description, inputSchema, allowedScenes, timeout,
                failurePolicy,
                newSensitivity == null ? ToolSensitivity.READ_ONLY : newSensitivity,
                requiresApproval, cacheTtl);
    }

    public ToolSpec withApproval(boolean approvalRequired) {
        return new ToolSpec(name, description, inputSchema, allowedScenes, timeout,
                failurePolicy, sensitivity, approvalRequired, cacheTtl);
    }

    public ToolSpec withCacheTtl(Duration newCacheTtl) {
        return new ToolSpec(name, description, inputSchema, allowedScenes, timeout,
                failurePolicy, sensitivity, requiresApproval,
                newCacheTtl == null ? Duration.ZERO : newCacheTtl);
    }

    public long timeoutMs() {
        return timeout == null ? 0 : timeout.toMillis();
    }
}
