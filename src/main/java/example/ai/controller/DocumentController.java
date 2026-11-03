package example.ai.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import example.ai.service.DocumentIngestionService;
import example.ai.service.DocumentIngestionService.IngestionResult;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentIngestionService ingestionService;
    private final Path storageDir;

    public DocumentController(DocumentIngestionService ingestionService,
            @Value("${jtms.documents.dir:./data/documents}") Path storageDir) {
        this.ingestionService = ingestionService;
        this.storageDir = storageDir.toAbsolutePath().normalize();
    }

    // The uploader is the authenticated caller (the token's "sub" claim), never a request
    // parameter: created_by / deactivated_by are audit columns, so the client must not choose them.
    @PostMapping(consumes = "multipart/form-data")
    public IngestionResult upload(@RequestParam("file") MultipartFile file,
            @RequestParam("name") String name,
            @RequestParam("isNew") boolean isNew,
            @AuthenticationPrincipal Jwt jwt) throws IOException {
        String userId = jwt.getSubject();
        Files.createDirectories(storageDir);

        // Never use the client's filename as a path: keep only a sanitized leaf name.
        String leaf = Path.of(file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename())
                .getFileName().toString();
        Path target = storageDir.resolve(UUID.randomUUID() + "-" + leaf);
        file.transferTo(target);

        try {
            return ingestionService.ingest(name, userId, target, isNew);
        } catch (RuntimeException e) {
            Files.deleteIfExists(target); // don't leave files that have no database record
            throw e;
        }
    }
}
