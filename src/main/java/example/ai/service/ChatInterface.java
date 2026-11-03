package example.ai.service;

import example.ai.model.ChatAnswer;

public interface ChatInterface {

    public ChatAnswer modelCall(String prompt, String userId, String conversationId);

}
