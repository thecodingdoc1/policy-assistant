package example.ai.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import example.ai.config.SecurityConfig;
import example.ai.service.DocumentIngestionService;
import example.ai.service.DocumentIngestionService.IngestionResult;

// @WebMvcTest doesn't pick up @Configuration classes, so without @Import it would run with
// Spring Boot's default security chain (CSRF on, no JWT) instead of ours.
@WebMvcTest(DocumentController.class)
@Import(SecurityConfig.class)
class DocumentControllerTest {

    @TempDir
    static Path storageDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("jtms.documents.dir", () -> storageDir.toString());
    }

    @BeforeEach
    void emptyStorage() throws Exception {
        File[] files = storageDir.toFile().listFiles();
        if (files != null) {
            for (File f : files) {
                Files.delete(f.toPath());
            }
        }
    }

    @Autowired
    MockMvc mvc;

    @MockBean
    DocumentIngestionService ingestion;

    // jwt() below supplies an already-authenticated token, so the decoder is never called; it only
    // has to exist because SecurityConfig's resource-server setup requires one.
    @MockBean
    JwtDecoder jwtDecoder;

    @Test
    void uploader_isTakenFromTheToken_notFromARequestParameter() throws Exception {
        when(ingestion.ingest(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(new IngestionResult(UUID.randomUUID(), "policy", 1));

        mvc.perform(multipart("/api/v1/documents").file(file())
                .param("name", "policy").param("isNew", "true")
                .param("userId", "mallory") // ignored: no such parameter any more
                .with(jwt().jwt(j -> j.subject("alice"))))
                .andExpect(status().isOk());

        verify(ingestion).ingest(eq("policy"), eq("alice"), any(), eq(true));
    }

    @Test
    void noToken_is401_andNothingIsIngested() throws Exception {
        mvc.perform(multipart("/api/v1/documents").file(file()).param("name", "policy").param("isNew", "true"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(ingestion);
    }

    @Test
    void upload_success_returnsResult() throws Exception {
        UUID id = UUID.randomUUID();
        when(ingestion.ingest(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(new IngestionResult(id, "policy", 3));

        mvc.perform(upload()).andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(id.toString()))
                .andExpect(jsonPath("$.chunkCount").value(3));
    }

    @Test
    void invalidInput_maps_to_400_withFunctionMessage() throws Exception {
        when(ingestion.ingest(anyString(), anyString(), any(), anyBoolean()))
                .thenThrow(sqlError("22023", "Chunk 2 embedding has 10 dimensions, expected 768"));

        mvc.perform(upload()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Chunk 2 embedding has 10 dimensions, expected 768"));
    }

    @Test
    void alreadyExists_maps_to_409() throws Exception {
        when(ingestion.ingest(anyString(), anyString(), any(), anyBoolean()))
                .thenThrow(sqlError("23505", "A document named \"policy\" already exists"));

        mvc.perform(upload()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A document named \"policy\" already exists"));
    }

    @Test
    void noActiveDocument_maps_to_404() throws Exception {
        when(ingestion.ingest(anyString(), anyString(), any(), anyBoolean()))
                .thenThrow(sqlError("P0002", "No active document named \"ghost\" exists"));

        mvc.perform(upload()).andExpect(status().isNotFound());
    }

    @Test
    void unknownDatabaseFailure_maps_to_500_withoutLeakingDetails() throws Exception {
        when(ingestion.ingest(anyString(), anyString(), any(), anyBoolean()))
                .thenThrow(new DataAccessResourceFailureException("connection to db.internal:5432 refused"));

        mvc.perform(upload()).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("db.internal"))));
    }

    @Test
    void illegalArgument_maps_to_400() throws Exception {
        when(ingestion.ingest(anyString(), anyString(), any(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("Document produced no chunks"));

        mvc.perform(upload()).andExpect(status().isBadRequest());
    }

    @Test
    void missingParameter_maps_to_400() throws Exception {
        mvc.perform(multipart("/api/v1/documents").file(file()).param("name", "policy").with(alice()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void failedIngestion_doesNotLeaveTheUploadedFileBehind() throws Exception {
        when(ingestion.ingest(anyString(), anyString(), any(), anyBoolean()))
                .thenThrow(sqlError("23505", "exists"));

        mvc.perform(upload()).andExpect(status().isConflict());

        File[] leftovers = storageDir.toFile().listFiles();
        org.assertj.core.api.Assertions.assertThat(leftovers).isNullOrEmpty();
    }

    @Test
    void clientFilename_cannotEscapeTheStorageDirectory() throws Exception {
        when(ingestion.ingest(anyString(), anyString(), any(), anyBoolean()))
                .thenAnswer(inv -> {
                    Path saved = inv.getArgument(2);
                    org.assertj.core.api.Assertions.assertThat(saved.normalize().startsWith(storageDir.toAbsolutePath().normalize()))
                            .isTrue();
                    return new IngestionResult(UUID.randomUUID(), "policy", 1);
                });

        MockMultipartFile evil = new MockMultipartFile("file", "../../evil.txt", "text/plain", "x".getBytes());
        mvc.perform(multipart("/api/v1/documents").file(evil)
                .param("name", "policy").param("isNew", "true").with(alice()))
                .andExpect(status().isOk());
    }

    // ---------- helpers ----------

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "policy.txt", "text/plain", "hello".getBytes());
    }

    private static MockMultipartHttpServletRequestBuilder upload() {
        return (MockMultipartHttpServletRequestBuilder) multipart("/api/v1/documents").file(file())
                .param("name", "policy").param("isNew", "true").with(alice());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor alice() {
        return jwt().jwt(j -> j.subject("alice"));
    }

    @Test
    void unversionedPath_isDenied_evenWithAValidToken() throws Exception {
        // SecurityConfig ends with anyRequest().denyAll(): anything outside /api/v1/** is 403.
        mvc.perform(multipart("/api/documents").file(file())
                .param("name", "policy").param("isNew", "true").with(alice()))
                .andExpect(status().isForbidden());
    }

    /** Shaped like what pgjdbc/Spring produce for a RAISE EXCEPTION inside upsert_document(). */
    private static UncategorizedSQLException sqlError(String sqlState, String message) {
        SQLException cause = new SQLException(
                "ERROR: " + message + "\n  Where: PL/pgSQL function upsert_document(...) line 42 at RAISE", sqlState);
        return new UncategorizedSQLException("PreparedStatementCallback", "SELECT upsert_document(...)", cause);
    }
}
