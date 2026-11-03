package example.ai.service;

import org.springframework.stereotype.Service;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.UUID;

import example.ai.model.ChatAnswer;

@Service
public class ChatService implements ChatInterface{

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private String systemPrompt = "You are an enterprise SAP S/4HANA support agent. Keep your answers under 3 sentences.";


    public ChatService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) 
    {
        this.chatMemory = new InMemoryChatMemory();


        this.chatClient = chatClientBuilder
                        .defaultAdvisors(new MessageChatMemoryAdvisor(chatMemory))
                        .defaultAdvisors(new QuestionAnswerAdvisor(vectorStore, SearchRequest.defaults().withTopK(3)))
                        .build();
    }

    @Override
    public ChatAnswer modelCall(String prompt, String userId, String conversationId)
    {
        // Memory is keyed by the caller, so one user can never see another user's history. Without
        // a conversationId the question is one-off: a fresh key, cleared afterwards. Older answers
        // in history can otherwise outweigh newly retrieved context (e.g. after a policy update).
        boolean oneOff = conversationId == null || conversationId.isBlank();
        String memoryKey = userId + ":" + (oneOff ? UUID.randomUUID() : conversationId);

        ChatResponse response;
        try {
            response = this.chatClient.prompt()
                    .advisors(a -> a.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, memoryKey))
                    .user(prompt)
                    .system(systemPrompt)
                    .functions("getPurchaseOrderStatus")
                    .call()
                    .chatResponse();
        } finally {
            if (oneOff) {
                chatMemory.clear(memoryKey);
            }
        }

        // QuestionAnswerAdvisor puts the chunks it injected into the prompt on the response
        // metadata, so the sources returned are exactly what the model was given.
        @SuppressWarnings("unchecked")
        List<Document> retrieved = (List<Document>) response.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        List<ChatAnswer.Source> sources = retrieved == null ? List.of()
                : retrieved.stream().map(ChatAnswer.Source::from).toList();

        return new ChatAnswer(response.getResult().getOutput().getContent(), sources);
    }
}
