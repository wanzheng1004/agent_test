package com.bridge.agent.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThinkResultParserTest {

    private final ThinkResultParser parser = new ThinkResultParser();

    @Test
    void parsesValidReactOutput() {
        ThinkResult result = parser.parse("""
                Thought: Need standard lookup.
                Action: retrieve_standard
                ActionInput: {"defectQuery":"pier crack"}
                """);

        assertThat(result.thought()).isEqualTo("Need standard lookup.");
        assertThat(result.action()).isEqualTo("retrieve_standard");
        assertThat(result.actionInput()).isEqualTo("{\"defectQuery\":\"pier crack\"}");
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void marksMissingActionInvalid() {
        ThinkResult result = parser.parse("""
                Thought: Missing the action line.
                ActionInput: {}
                """);

        assertThat(result.isValid()).isFalse();
        assertThat(result.action()).isNull();
    }

    @Test
    void ignoresExtraTextAroundProtocol() {
        ThinkResult result = parser.parse("""
                Sure.

                Thought: Ask for width.
                Action: ASK_USER
                ActionInput: Please provide crack width.

                Thanks.
                """);

        assertThat(result.action()).isEqualTo("ASK_USER");
        assertThat(result.actionInput()).contains("Please provide crack width.");
    }

    @Test
    void preservesJsonLikeActionInput() {
        ThinkResult result = parser.parse("""
                Thought: Finish.
                Action: FINISH
                ActionInput: {"component":"0# pier","grade":3,"nested":{"a":1}}
                """);

        assertThat(result.action()).isEqualTo("FINISH");
        assertThat(result.actionInput()).contains("\"nested\"");
    }
}
