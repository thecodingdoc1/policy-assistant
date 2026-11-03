package example.ai.vector;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Read-only {@link VectorStore} over the custom documents / document_chunks schema.
 *
 * Spring AI's own PgVectorStore assumes a single auto-generated table and cannot express
 * "only search the active version of each document", so retrieval is implemented here.
 * Writes deliberately do not go through this class: they must carry document-level data
 * (name, uploader, source path, is_new) and run through upsert_document() atomically.
 */
@Component
public class JtmsVectorStore implements VectorStore {

    private static final String SEARCH_SQL = """
            SELECT c.id, c.document_chunk, c.metadata::text AS metadata, d.name AS document_name,
                   d.id AS document_id, d.effective_from,
                   -- version number derived from history (nth upload of this name), not stored
                   (SELECT count(*) FROM documents v
                     WHERE v.name = d.name AND v.effective_from <= d.effective_from) AS document_version,
                   c.embedding <=> ?::vector AS distance
            FROM document_chunks c
            JOIN documents d ON d.id = c.document_id
            WHERE d.is_active
              AND (? <= 0 OR c.embedding <=> ?::vector <= ?)
            ORDER BY distance
            LIMIT ?
            """;

    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    public JtmsVectorStore(JdbcTemplate jdbc, EmbeddingModel embeddingModel, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public void add(List<Document> documents) {
        throw new UnsupportedOperationException(
                "Ingest through DocumentIngestionService so upsert_document() can version the document atomically");
    }

    @Override
    public Optional<Boolean> delete(List<String> idList) {
        throw new UnsupportedOperationException(
                "Chunks are never deleted: superseded versions are retained for auditability");
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        if (request.hasFilterExpression()) {
            throw new UnsupportedOperationException("Metadata filter expressions are not supported");
        }

        String queryVector = toVectorLiteral(embeddingModel.embed(request.getQuery()));
        double maxDistance = 1.0 - request.getSimilarityThreshold();

        return jdbc.query(SEARCH_SQL, (rs, rowNum) -> {
            Map<String, Object> metadata = parseMetadata(rs.getString("metadata"));
            // Citation fields, surfaced to the caller as ChatAnswer.Source
            metadata.put("document_name", rs.getString("document_name"));
            metadata.put("document_id", rs.getString("document_id"));
            metadata.put("document_version", rs.getInt("document_version"));
            metadata.put("effective_from", rs.getObject("effective_from", OffsetDateTime.class).toString());
            metadata.put("distance", rs.getDouble("distance"));
            return new Document(rs.getString("id"), rs.getString("document_chunk"), metadata);
        }, queryVector, request.getSimilarityThreshold(), queryVector, maxDistance, request.getTopK());
    }

    static String toVectorLiteral(List<Double> embedding) {
        return embedding.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    private Map<String, Object> parseMetadata(String json) {
        try {
            return objectMapper.readValue(json, METADATA_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt chunk metadata JSON", e);
        }
    }
}
