package com.bridge.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the explicit ReAct text protocol used by this project.
 */
@Component
public class ThinkResultParser {

    private static final Logger log = LoggerFactory.getLogger(ThinkResultParser.class);

    private static final Pattern THOUGHT_PATTERN =
            Pattern.compile("Thought:\\s*(.+?)(?=\\RAction:)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_PATTERN =
            Pattern.compile("Action:\\s*([^\\r\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INPUT_PATTERN =
            Pattern.compile("ActionInput:\\s*(.+)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    public ThinkResult parse(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            log.warn("LLM output is empty, returning invalid ThinkResult");
            return new ThinkResult("", null, "");
        }

        String thought = extractGroup(THOUGHT_PATTERN, llmOutput);
        String action = extractGroup(ACTION_PATTERN, llmOutput);
        String actionInput = extractGroup(INPUT_PATTERN, llmOutput);

        if (action != null) {
            action = normalizeAction(action);
        }

        ThinkResult result = new ThinkResult(
                thought == null ? "" : thought.trim(),
                action,
                actionInput == null ? "" : actionInput.trim()
        );

        if (!result.isValid()) {
            log.warn("Failed to parse ThinkResult from output:\n{}", llmOutput);
        }
        return result;
    }

    public String diagnoseProblem(ThinkResult bad, String rawOutput) {
        if (bad.action() == null || bad.action().isBlank()) {
            if (rawOutput == null || !rawOutput.contains("Action:")) {
                return "Missing 'Action:' line. Use exactly Thought/Action/ActionInput.";
            }
            return "Action is empty. Choose one tool, FINISH, or ASK_USER.";
        }
        return "Output format is invalid. Use exactly Thought/Action/ActionInput.";
    }

    private String normalizeAction(String action) {
        String trimmed = action.trim();
        if (trimmed.equalsIgnoreCase("FINISH")) {
            return "FINISH";
        }
        if (trimmed.equalsIgnoreCase("ASK_USER")) {
            return "ASK_USER";
        }
        return trimmed;
    }

    private String extractGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
