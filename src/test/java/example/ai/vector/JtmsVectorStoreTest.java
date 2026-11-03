package example.ai.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;

import example.ai.service.DocumentIngestionService;

/** Retrieval and ingestion against a real pgvector database; only the embedding model is mocked. */
@Testcontainers
class JtmsVectorStoreTest {

    private static final int DIMS = 768;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static JdbcTemplate jdbc;

    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    ObjectMapper mapper = new ObjectMapper();
    JtmsVectorStore store;
    DocumentIngestionService ingestion;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE document_chunks, documents");
        store = new JtmsVectorStore(jdbc, embeddingModel, mapper);
        ingestion = new DocumentIngestionService(jdbc, embeddingModel, mapper);
    }

    // ---------- retrieval ----------

    @Test
    void search_returnsNearestChunksFirstAndRespectsTopK() {
        seed("policy", "alice", true, chunk("far", axis(0)), chunk("near", axis(1)), chunk("mid", mix(1, 0)));
        when(embeddingModel.embed(anyString())).thenReturn(toList(axis(1)));

        List<Document> results = store.similaritySearch(SearchRequest.query("q").withTopK(2));

        assertThat(results).extracting(Document::getContent).containsExactly("near", "mid");
        assertThat(results.get(0).getMetadata()).containsEntry("document_name", "policy")
                .containsEntry("document_version", 1);
        assertThat((double) results.get(0).getMetadata().get("distance")).isLessThan(0.0001);
    }

    @Test
    void search_ignoresSupersededVersions() {
        seed("policy", "alice", true, chunk("old text", axis(1)));
        seed("policy", "bob", false, chunk("new text", axis(1)));
        when(embeddingModel.embed(anyString())).thenReturn(toList(axis(1)));

        List<Document> results = store.similaritySearch(SearchRequest.query("q").withTopK(10));

        assertThat(results).extracting(Document::getContent).containsExactly("new text");
        // citation fields: the active row is the 2nd upload of this name
        assertThat(results.get(0).getMetadata())
                .containsEntry("document_name", "policy")
                .containsEntry("document_version", 2)
                .containsKeys("document_id", "effective_from");
    }

    @Test
    void search_appliesSimilarityThreshold() {
        seed("policy", "alice", true, chunk("same", axis(1)), chunk("orthogonal", axis(0)));
        when(embeddingModel.embed(anyString())).thenReturn(toList(axis(1)));

        List<Document> results = store.similaritySearch(
                SearchRequest.query("q").withTopK(10).withSimilarityThreshold(0.9));

        assertThat(results).extracting(Document::getContent).containsExactly("same");
    }

    @Test
    void search_withNoDocuments_returnsEmpty() {
        when(embeddingModel.embed(anyString())).thenReturn(toList(axis(1)));

        assertThat(store.similaritySearch("anything")).isEmpty();
    }

    @Test
    void writesAndFiltersAreRejected() {
        assertThatThrownBy(() -> store.add(List.of(new Document("x")))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> store.delete(List.of("id"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> store.similaritySearch(SearchRequest.query("q").withFilterExpression("a == 'b'")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------- ingestion ----------

    @Test
    void ingest_storesChunksAndThenSearchFindsThem(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("policy.txt"), "Purchase orders over 5000 need approval.");
        when(embeddingModel.embed(org.mockito.ArgumentMatchers.<List<String>>any()))
                .thenAnswer(inv -> ((List<String>) inv.getArgument(0)).stream().map(t -> toList(axis(2))).toList());
        when(embeddingModel.embed(anyString())).thenReturn(toList(axis(2)));

        var result = ingestion.ingest("policy", "alice", file, true);

        assertThat(result.chunkCount()).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE name = 'policy' AND is_active", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_document_path FROM documents", String.class))
                .isEqualTo(file.toString());
        assertThat(store.similaritySearch("approval limit")).isNotEmpty();
    }

    @Test
    void ingest_duplicateNewDocument_isRejected(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("p.txt"), "Some policy text.");
        when(embeddingModel.embed(org.mockito.ArgumentMatchers.<List<String>>any()))
                .thenAnswer(inv -> ((List<String>) inv.getArgument(0)).stream().map(t -> toList(axis(2))).toList());

        ingestion.ingest("policy", "alice", file, true);

        assertThatThrownBy(() -> ingestion.ingest("policy", "bob", file, true)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void ingest_emptyFile_isRejectedBeforeAnyDatabaseWrite(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("empty.txt"), "");

        assertThatThrownBy(() -> ingestion.ingest("policy", "alice", file, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents", Integer.class)).isZero();
    }

    // ---------- helpers ----------

    private record Chunk(String text, double[] embedding) {}

    private static Chunk chunk(String text, double[] embedding) {
        return new Chunk(text, embedding);
    }

    private void seed(String name, String user, boolean isNew, Chunk... chunks) {
        List<java.util.Map<String, Object>> payload = new ArrayList<>();
        for (Chunk c : chunks) {
            payload.add(java.util.Map.of("text", c.text(), "embedding", toList(c.embedding())));
        }
        try {
            jdbc.queryForObject("SELECT upsert_document(?, ?, ?, ?, ?::jsonb)", Object.class,
                    name, user, "/docs/" + name + ".txt", isNew, mapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Unit vector along one axis; different axes are orthogonal (cosine distance 1). */
    private static double[] axis(int i) {
        double[] v = new double[DIMS];
        v[i] = 1.0;
        return v;
    }

    /** Equal mix of two axes: cosine distance ~0.29 from either. */
    private static double[] mix(int a, int b) {
        double[] v = new double[DIMS];
        v[a] = 1.0;
        v[b] = 1.0;
        return v;
    }

    private static List<Double> toList(double[] v) {
        List<Double> list = new ArrayList<>(v.length);
        for (double d : v) {
            list.add(d);
        }
        return list;
    }
}
