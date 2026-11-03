package example.ai.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises the upsert_document() function against a real pgvector Postgres, with the schema
 * built by the same Flyway migrations the app uses.
 */
@Testcontainers
class UpsertDocumentFunctionTest {

    private static final int DIMS = 768;
    private static final String PATH = "/docs/policy.txt";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void cleanTables() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE document_chunks, documents");
        }
    }

    // ---------- happy path ----------

    @Test
    void newDocument_isInsertedActiveWithAllChunks() throws Exception {
        UUID id = upsert("policy", "alice", true, chunks(3));

        try (Connection c = connect()) {
            assertThat(query(c, "SELECT count(*) FROM documents")).isEqualTo(1);
            assertThat(query(c, "SELECT count(*) FROM documents WHERE is_active AND created_by = 'alice' AND id = '" + id + "'"))
                    .isEqualTo(1);
            assertThat(query(c, "SELECT count(*) FROM document_chunks WHERE document_id = '" + id + "'")).isEqualTo(3);
            assertThat(query(c, "SELECT count(*) FROM document_chunks WHERE vector_dims(embedding) = " + DIMS)).isEqualTo(3);
            assertThat(query(c, "SELECT count(*) FROM document_chunks WHERE metadata = '{}'::jsonb")).isEqualTo(3);
        }
    }

    @Test
    void chunkMetadata_isStoredWhenSupplied() throws Exception {
        String json = "[{\"text\":\"t\",\"embedding\":" + embedding() + ",\"metadata\":{\"page\":4}}]";
        upsert("policy", "alice", true, json);

        try (Connection c = connect()) {
            assertThat(query(c, "SELECT count(*) FROM document_chunks WHERE metadata->>'page' = '4'")).isEqualTo(1);
        }
    }

    // ---------- versioning ----------

    @Test
    void update_supersedesOldVersionAndKeepsHistory() throws Exception {
        UUID v1 = upsert("policy", "alice", true, chunks(2));
        UUID v2 = upsert("policy", "bob", false, chunks(4));

        assertThat(v2).isNotEqualTo(v1);
        try (Connection c = connect()) {
            assertThat(query(c, "SELECT count(*) FROM documents WHERE name = 'policy'")).isEqualTo(2);
            assertThat(query(c, "SELECT count(*) FROM documents WHERE name = 'policy' AND is_active")).isEqualTo(1);

            // old version: deactivated and attributed, never deleted; its chunks are untouched
            assertThat(query(c, "SELECT count(*) FROM documents WHERE id = '" + v1 + "' AND NOT is_active "
                    + "AND deactivated_by = 'bob' AND deactivate_date IS NOT NULL AND effective_to IS NOT NULL")).isEqualTo(1);
            assertThat(query(c, "SELECT count(*) FROM document_chunks WHERE document_id = '" + v1 + "'")).isEqualTo(2);

            // new version: active, open-ended
            assertThat(query(c, "SELECT count(*) FROM documents WHERE id = '" + v2 + "' AND is_active "
                    + "AND effective_to IS NULL AND deactivated_by IS NULL")).isEqualTo(1);
            assertThat(query(c, "SELECT count(*) FROM document_chunks WHERE document_id = '" + v2 + "'")).isEqualTo(4);
        }
    }

    @Test
    void update_oldEffectiveToEqualsNewEffectiveFrom() throws Exception {
        UUID v1 = upsert("policy", "alice", true, chunks(1));
        UUID v2 = upsert("policy", "bob", false, chunks(1));

        try (Connection c = connect()) {
            assertThat(timestamp(c, "SELECT effective_to FROM documents WHERE id = '" + v1 + "'"))
                    .isEqualTo(timestamp(c, "SELECT effective_from FROM documents WHERE id = '" + v2 + "'"));
        }
    }

    @Test
    void differentNames_areIndependent() throws Exception {
        upsert("policy-a", "alice", true, chunks(1));
        upsert("policy-b", "alice", true, chunks(1));

        try (Connection c = connect()) {
            assertThat(query(c, "SELECT count(*) FROM documents WHERE is_active")).isEqualTo(2);
        }
    }

    // ---------- is_new contract ----------

    @Test
    void isNewTrue_whenActiveExists_isRejected() throws Exception {
        upsert("policy", "alice", true, chunks(1));

        assertSqlState("23505", () -> upsert("policy", "bob", true, chunks(1)));
        try (Connection c = connect()) {
            assertThat(query(c, "SELECT count(*) FROM documents")).isEqualTo(1);
        }
    }

    @Test
    void isNewFalse_whenNoActiveExists_isRejected() {
        assertSqlState("P0002", () -> upsert("ghost", "alice", false, chunks(1)));
    }

    @Test
    void isNewFalse_doesNotMatchAnInactiveOnlyName() throws Exception {
        // a name whose only rows are inactive has no active version to supersede
        upsert("policy", "alice", true, chunks(1));
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("UPDATE documents SET is_active = false WHERE name = 'policy'");
        }
        assertSqlState("P0002", () -> upsert("policy", "bob", false, chunks(1)));
    }

    // ---------- validation ----------

    @Test
    void blankName_isRejected() {
        assertSqlState("22023", () -> upsert("  ", "alice", true, chunks(1)));
    }

    @Test
    void nullName_isRejected() {
        assertSqlState("22023", () -> upsert(null, "alice", true, chunks(1)));
    }

    @Test
    void blankUser_isRejected() {
        assertSqlState("22023", () -> upsert("policy", "", true, chunks(1)));
    }

    @Test
    void blankSourcePath_isRejected() {
        assertSqlState("22023", () -> call("policy", "alice", " ", true, chunks(1)));
    }

    @Test
    void nullIsNewFlag_isRejected() {
        assertSqlState("22023", () -> {
            try (Connection c = connect();
                    PreparedStatement ps = c.prepareStatement("SELECT upsert_document(?, ?, ?, NULL, ?::jsonb)")) {
                ps.setString(1, "policy");
                ps.setString(2, "alice");
                ps.setString(3, PATH);
                ps.setString(4, chunks(1));
                ps.executeQuery();
            }
        });
    }

    @Test
    void emptyChunkList_isRejected() {
        assertSqlState("22023", () -> upsert("policy", "alice", true, "[]"));
    }

    @Test
    void chunksNotAnArray_isRejected() {
        assertSqlState("22023", () -> upsert("policy", "alice", true, "{\"text\":\"x\"}"));
    }

    @Test
    void emptyChunkText_isRejected() {
        String json = "[{\"text\":\" \",\"embedding\":" + embedding() + "}]";
        assertSqlState("22023", () -> upsert("policy", "alice", true, json));
    }

    @Test
    void missingEmbedding_isRejected() {
        assertSqlState("22023", () -> upsert("policy", "alice", true, "[{\"text\":\"x\"}]"));
    }

    @Test
    void wrongDimensionEmbedding_isRejected() {
        String json = "[{\"text\":\"x\",\"embedding\":" + embedding(DIMS - 1) + "}]";
        assertSqlState("22023", () -> upsert("policy", "alice", true, json));
    }

    @Test
    void oneBadChunk_rejectsWholeBatchWithNoPartialWrites() throws Exception {
        String good = "{\"text\":\"ok\",\"embedding\":" + embedding() + "}";
        String bad = "{\"text\":\"bad\",\"embedding\":" + embedding(10) + "}";

        assertSqlState("22023", () -> upsert("policy", "alice", true, "[" + good + "," + bad + "]"));

        try (Connection c = connect()) {
            assertThat(query(c, "SELECT count(*) FROM documents")).isZero();
            assertThat(query(c, "SELECT count(*) FROM document_chunks")).isZero();
        }
    }

    @Test
    void failedUpdate_leavesActiveVersionUntouched() throws Exception {
        UUID v1 = upsert("policy", "alice", true, chunks(2));

        assertSqlState("22023", () -> upsert("policy", "bob", false, "[{\"text\":\"x\",\"embedding\":[1,2]}]"));

        try (Connection c = connect()) {
            assertThat(query(c, "SELECT count(*) FROM documents WHERE id = '" + v1 + "' AND is_active "
                    + "AND deactivated_by IS NULL AND effective_to IS NULL")).isEqualTo(1);
            assertThat(query(c, "SELECT count(*) FROM documents")).isEqualTo(1);
        }
    }

    // ---------- schema guarantees ----------

    @Test
    void deletingADocumentWithChunks_isRestricted() throws Exception {
        UUID id = upsert("policy", "alice", true, chunks(1));

        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute("DELETE FROM documents WHERE id = '" + id + "'"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("23503"); // foreign_key_violation
        }
    }

    @Test
    void partialUniqueIndex_blocksTwoActiveRowsEvenOutsideTheFunction() throws Exception {
        upsert("policy", "alice", true, chunks(1));

        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute("INSERT INTO documents (name, is_active, source_document_path, created_by) "
                    + "VALUES ('policy', true, '/x', 'mallory')"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("23505");
        }
    }

    // ---------- concurrency ----------

    @Test
    void concurrentCreatesOfSameNewName_exactlyOneWins() throws Exception {
        List<Outcome> outcomes = race(
                () -> upsert("policy", "alice", true, chunks(1)),
                () -> upsert("policy", "bob", true, chunks(1)));

        assertThat(outcomes.stream().filter(Outcome::ok)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.ok()).map(Outcome::sqlState)).containsExactly("23505");
        try (Connection c = connect()) {
            assertThat(query(c, "SELECT count(*) FROM documents WHERE name = 'policy' AND is_active")).isEqualTo(1);
        }
    }

    @Test
    void concurrentUpdatesOfSameDocument_exactlyOneWinsAndHistoryIsConsistent() throws Exception {
        upsert("policy", "alice", true, chunks(1));

        List<Outcome> outcomes = race(
                () -> upsert("policy", "bob", false, chunks(1)),
                () -> upsert("policy", "carol", false, chunks(1)));

        // The loser blocks on the row lock, then finds no active row left.
        assertThat(outcomes.stream().filter(Outcome::ok)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.ok()).map(Outcome::sqlState)).containsExactly("P0002");
        try (Connection c = connect()) {
            assertThat(query(c, "SELECT count(*) FROM documents WHERE name = 'policy'")).isEqualTo(2);
            assertThat(query(c, "SELECT count(*) FROM documents WHERE name = 'policy' AND is_active")).isEqualTo(1);
        }
    }

    // ---------- helpers ----------

    private record Outcome(boolean ok, String sqlState) {}

    @SafeVarargs
    private static List<Outcome> race(Callable<UUID>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CyclicBarrier start = new CyclicBarrier(tasks.length);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<UUID> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        task.call();
                        return new Outcome(true, null);
                    } catch (SQLException e) {
                        return new Outcome(false, e.getSQLState());
                    }
                }));
            }
            List<Outcome> results = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private interface SqlAction {
        void run() throws Exception;
    }

    private static void assertSqlState(String expected, SqlAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(expected);
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static UUID upsert(String name, String user, boolean isNew, String chunksJson) throws SQLException {
        return call(name, user, PATH, isNew, chunksJson);
    }

    private static UUID call(String name, String user, String path, boolean isNew, String chunksJson)
            throws SQLException {
        try (Connection c = connect();
                PreparedStatement ps = c.prepareStatement("SELECT upsert_document(?, ?, ?, ?, ?::jsonb)")) {
            ps.setString(1, name);
            ps.setString(2, user);
            ps.setString(3, path);
            ps.setBoolean(4, isNew);
            ps.setString(5, chunksJson);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static int query(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Timestamp timestamp(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getTimestamp(1);
        }
    }

    private static String embedding() {
        return embedding(DIMS);
    }

    private static String embedding(int dims) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dims; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(0.01 * (i % 10 + 1));
        }
        return sb.append(']').toString();
    }

    private static String chunks(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"text\":\"chunk ").append(i).append("\",\"embedding\":").append(embedding()).append('}');
        }
        return sb.append(']').toString();
    }
}
