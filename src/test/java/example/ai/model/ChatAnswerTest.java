package example.ai.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class ChatAnswerTest {

    @Test
    void source_isBuiltFromTheMetadataJtmsVectorStoreAttaches() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("document_name", "procurement-policy");
        metadata.put("document_version", 2);
        metadata.put("document_id", "9696dd19-4cb8-40ae-8166-4c6135af165a");
        metadata.put("effective_from", "2026-11-04T15:00:00Z");
        metadata.put("distance", 0.18);
        Document chunk = new Document("chunk-1", "text", metadata);

        ChatAnswer.Source source = ChatAnswer.Source.from(chunk);

        assertThat(source).isEqualTo(new ChatAnswer.Source("procurement-policy", 2,
                "9696dd19-4cb8-40ae-8166-4c6135af165a", "2026-11-04T15:00:00Z", "chunk-1", 0.18));
    }

    @Test
    void source_toleratesMissingFields() {
        ChatAnswer.Source source = ChatAnswer.Source.from(new Document("chunk-1", "text", new HashMap<>()));

        assertThat(source.chunkId()).isEqualTo("chunk-1");
        assertThat(source.documentName()).isNull();
        assertThat(source.documentVersion()).isNull();
        assertThat(source.distance()).isNull();
    }
}
