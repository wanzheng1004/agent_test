package com.bridge.agent.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    @Test
    void executesRegisteredToolAndPassesInputThrough() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("echo", "Echo input", "{\"value\":\"x\"}", input -> "seen:" + input);

        assertThat(registry.execute("echo", "{\"value\":\"abc\"}"))
                .isEqualTo("seen:{\"value\":\"abc\"}");
    }

    @Test
    void rejectsUnknownTool() {
        ToolRegistry registry = new ToolRegistry();

        assertThatThrownBy(() -> registry.execute("missing", "{}"))
                .isInstanceOf(InvalidActionException.class)
                .hasMessageContaining("Unknown tool");
    }

    @Test
    void wrapsToolExceptions() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("fail", "Fails", "{}", input -> {
            throw new IllegalStateException("boom");
        });

        assertThatThrownBy(() -> registry.execute("fail", "{}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void rendersTypedToolMetadata() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(ToolSpec.readOnly("lookup", "Lookup data", "{\"id\":\"x\"}"), input -> "ok");

        assertThat(registry.renderDescriptions(null))
                .contains("Tool: lookup")
                .contains("Sensitivity: READ_ONLY")
                .contains("Failure policy: RETURN_OBSERVATION");
    }
}
