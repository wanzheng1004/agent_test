package com.bridge.agent.llm;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

public interface AgentLlmClient {

    String complete(String systemPrompt,
                    List<Message> history,
                    String userMessage,
                    Map<String, Object> advisorParams);
}
