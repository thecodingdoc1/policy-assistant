package example.ai.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads a document from disk, chunks and embeds it, then hands the whole batch to the
 * upsert_document() function, which validates and versions it in one transaction.
 */
@Service
public class DocumentIngestionService {

    public record IngestionResult(UUID documentId, String name, int chunkCount) {}

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    public DocumentIngestionService(JdbcTemplate jdbc, EmbeddingModel embeddingModel, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
    }

    public IngestionResult ingest(String name, String userId, Path sourcePath, boolean isNew) {
        List<Document> chunks = new TokenTextSplitter()
                .apply(new TextReader(new FileSystemResource(sourcePath)).get());
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Document produced no chunks: " + sourcePath);
        }

        // Embed before touching the database so the row lock in upsert_document() is held
        // only for the SQL itself, not for slow model calls.
        List<List<Double>> embeddings = embeddingModel.embed(chunks.stream().map(Document::getContent).toList());

        UUID id = jdbc.queryForObject("SELECT upsert_document(?, ?, ?, ?, ?::jsonb)", UUID.class,
                name, userId, sourcePath.toString(), isNew, toChunksJson(chunks, embeddings));
        return new IngestionResult(id, name, chunks.size());
    }

    private String toChunksJson(List<Document> chunks, List<List<Double>> embeddings) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("text", chunks.get(i).getContent());
            chunk.put("embedding", embeddings.get(i));
            chunk.put("metadata", chunks.get(i).getMetadata());
            payload.add(chunk);
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize chunks", e);
        }
    }
}
