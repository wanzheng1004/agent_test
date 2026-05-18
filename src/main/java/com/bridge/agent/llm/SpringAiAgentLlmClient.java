package com.bridge.agent.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SpringAiAgentLlmClient implements AgentLlmClient {

    private final ChatClient chatClient;

    public SpringAiAgentLlmClient(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String complete(String systemPrompt,
                           List<Message> history,
                           String userMessage,
                           Map<String, Object> advisorParams) {
        return chatClient.prompt()
                .system(systemPrompt == null ? "" : systemPrompt)
                .messages(history == null ? List.of() : history)
                .user(userMessage == null ? "" : userMessage)
                .advisors(spec -> {
                    if (advisorParams != null) {
                        advisorParams.forEach(spec::param);
                    }
                })
                .call()
                .content();
    }
}
