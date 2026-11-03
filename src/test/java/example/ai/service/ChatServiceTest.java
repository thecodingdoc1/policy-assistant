package example.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import example.ai.model.ChatAnswer;

/** ChatService with a stub model and vector store: checks memory scoping and source mapping, no Ollama. */
class ChatServiceTest {

    ChatModel model = mock(ChatModel.class);
    VectorStore store = mock(VectorStore.class);
    List<Prompt> prompts = new ArrayList<>();
    ChatService service;

    @BeforeEach
    void setUp() {
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            prompts.add(inv.getArgument(0));
            return new ChatResponse(List.of(new Generation("answer " + prompts.size())));
        });
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("document_name", "procurement-policy");
        metadata.put("document_version", 2);
        metadata.put("distance", 0.34);
        when(store.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("chunk-1", "Up to $10,000: department manager.", metadata)));
        service = new ChatService(ChatClient.builder(model), store);
    }

    @Test
    void returnsTheAnswerAndTheRetrievedChunksAsSources() {
        ChatAnswer answer = service.modelCall("Who approves $8,000?", "alice", null);

        assertThat(answer.answer()).isEqualTo("answer 1");
        assertThat(answer.sources()).singleElement().satisfies(s -> {
            assertThat(s.documentName()).isEqualTo("procurement-policy");
            assertThat(s.documentVersion()).isEqualTo(2);
            assertThat(s.chunkId()).isEqualTo("chunk-1");
        });
    }

    @Test
    void oneOffQuestions_carryNoHistory() {
        service.modelCall("first question", "alice", null);
        service.modelCall("second question", "alice", null);

        assertThat(prompts.get(1).getContents()).doesNotContain("first question");
    }

    @Test
    void sameConversation_carriesHistory() {
        service.modelCall("first question", "alice", "c-1");
        service.modelCall("second question", "alice", "c-1");

        assertThat(prompts.get(1).getContents()).contains("first question");
    }

    @Test
    void sameConversationId_isNotSharedBetweenUsers() {
        service.modelCall("alice's secret question", "alice", "c-1");
        service.modelCall("bob's question", "bob", "c-1");

        assertThat(prompts.get(1).getContents()).doesNotContain("alice's secret question");
    }
}
