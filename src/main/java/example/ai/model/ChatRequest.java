package example.ai.model;

/**
 * @param conversationId optional. Send the same id to continue a conversation; omit it for a
 *                       one-off question with no chat history.
 */
public record ChatRequest(String prompt, String conversationId) {}
