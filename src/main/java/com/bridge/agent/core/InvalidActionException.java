package com.bridge.agent.core;

/**
 * LLM 选择了不存在的工具，或工具参数格式不合法时抛出
 *
 * <p>这个异常会被 ReActEngine 捕获，将错误信息作为 Observation
 * 注入下一轮 Prompt，让 LLM 自行纠正。
 */
public class InvalidActionException extends RuntimeException {
    public InvalidActionException(String message) {
        super(message);
    }
}
