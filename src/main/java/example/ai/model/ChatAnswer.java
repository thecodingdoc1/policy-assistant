package example.ai.model;

import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;

/**
 * The model's answer plus the exact passages that were put into its prompt. "Sources" are what
 * retrieval supplied, not a claim about which passage the model relied on: with no similarity
 * threshold, a question the documents don't cover still lists its (weak) nearest matches, which
 * the distance makes visible.
 */
public record ChatAnswer(String answer, List<Source> sources) {

    public record Source(String documentName, Integer documentVersion, String documentId,
            String effectiveFrom, String chunkId, Double distance) {

        /** Reads the fields JtmsVectorStore puts on every retrieved chunk. */
        public static Source from(Document chunk) {
            Map<String, Object> m = chunk.getMetadata();
            return new Source(
                    (String) m.get("document_name"),
                    m.get("document_version") instanceof Number n ? n.intValue() : null,
                    (String) m.get("document_id"),
                    (String) m.get("effective_from"),
                    chunk.getId(),
                    m.get("distance") instanceof Number n ? n.doubleValue() : null);
        }
    }
}
