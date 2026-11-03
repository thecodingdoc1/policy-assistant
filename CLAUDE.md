# CLAUDE.md

Guidance for working in this repository: a Spring AI service that answers questions over versioned
policy documents using retrieval-augmented generation (RAG). Setup and usage are in `README.md`.
If a `CLAUDE.local.md` exists, read it too (personal, git-ignored).

## Conventions

- **Verify claims by running them.** Don't describe a feature as working until it has been run.
  Framework behavior on Spring AI 1.0.0-M1 is easy to assume wrong; check the jar (`javap`) or run it.
- Keep design rationale in sync with the migrations when either changes (see "Schema" below).
- Schema changes go in **new** Flyway migrations (`V3__...`). Never edit an applied migration.
- Keep the demo and evaluation material fictional. No real organization's data or policies.

## Commands

```bash
docker compose up -d                 # Postgres + pgvector on host port 5433 (named volume pgdata)
scripts/dev-token.sh <subject>       # creates dev-keys/ on first run, prints a signed JWT
mvn spring-boot:run                  # app on :8080 (needs Ollama running locally)
mvn test                             # 55 tests; Testcontainers needs Docker running
TOKEN=$(scripts/dev-token.sh eval) eval/run-eval.sh   # eval run; grade against eval/EVAL_SET.md
```

## Stack

- Java 21, Spring Boot 3.3.0, Spring AI **1.0.0-M1** (pre-release milestone; see the tool-calling gap below).
- Local Ollama: chat model `qwen2.5-coder` (7.6B, temperature 0), embedding model
  `nomic-embed-text` (768 dimensions, no dimension override).
- Postgres 16 + pgvector (`pgvector/pgvector:pg16`) with a custom two-table schema; Flyway migrations.
- Spring Security OAuth2 resource server: RS256 JWTs verified with a local public key
  (`spring.security.oauth2.resourceserver.jwt.public-key-location`, default `file:./dev-keys/public.pem`).
- Multi-stage `Dockerfile` (Maven build → fat JAR → `eclipse-temurin:21-jre-alpine`). The app isn't in
  `docker-compose.yml`; in a container, set `POSTGRES_URL`, `HOME_MACHINE_ENDPOINT`
  (`http://host.docker.internal:11434`) and `JWT_PUBLIC_KEY_LOCATION`.
- Tests: JUnit 5, Testcontainers (real pgvector), MockMvc + `spring-security-test`.

## Code map (`src/main/java/example/ai/`)

| Path | Role |
|---|---|
| `config/AiConfig` | Stubbed `getPurchaseOrderStatus` tool: `PO-123` → "Approved", anything else → "Denied". Deliberately minimal. |
| `config/SecurityConfig` | JWT resource server; `/api/v1/**` authenticated, everything else `denyAll`; CSRF off, stateless. |
| `controller/ChatController` | `POST /api/v1/chat` `{prompt, conversationId?}` → `{answer, sources[]}`. |
| `controller/DocumentController` | `POST /api/v1/documents` multipart `file`, `name`, `isNew`; uploader = token `sub`. Original file saved under `jtms.documents.dir`. |
| `controller/GlobalExceptionHandler` | RFC 7807 `ProblemDetail`: SQLSTATE `22023`→400, `23505`→409, `P0002`→404; other errors → 500 with a correlation id only. |
| `service/ChatService` | `ChatClient` + `MessageChatMemoryAdvisor` (in-memory, keyed `<sub>:<conversationId>`; no id = one-off, cleared after the call) + `QuestionAnswerAdvisor` (topK 3, no similarity threshold). Sources = the advisor's retrieved documents (`RETRIEVED_DOCUMENTS` response metadata), i.e. exactly what was in the prompt. |
| `service/DocumentIngestionService` | `TextReader` + `TokenTextSplitter` (defaults: ~800 tokens, no overlap) → batch embed → `SELECT upsert_document(...)`. Embeds **before** the database call so the row lock is held only for the SQL. |
| `vector/JtmsVectorStore` | Read-only `VectorStore` over the custom schema: active versions only, cosine `<=>`, adds `document_name/id/version/effective_from/distance` metadata. `add`/`delete`/filter expressions throw on purpose. |
| `model/ChatRequest`, `model/ChatAnswer` | Request/response records. |

Other folders: `src/main/resources/db/migration/` (V1 schema, V2 `upsert_document`), `demo/`
(fictional policies + load scripts), `eval/` (15-question set, runner, results), `scripts/`.

## Schema

DDL: `db/migration/V1__init_schema.sql`. Write path: the PL/pgSQL function in `V2__upsert_document.sql`.

- **One `documents` row per version**, linked by `name`. At most one active row per name, enforced by
  the partial unique index `unique_name_active`, not only by application code. A superseded row gets
  `is_active = false`, `deactivated_by`, `deactivate_date` and `effective_to`; the active row's
  `effective_to` is NULL. The version number in sources is derived from history, not stored.
- **Full history is kept** (never overwritten); no version-diff logic.
- **Audit trail on the row itself** (`created_by`, `deactivated_by`, effective dates), not a separate
  audit table. Attribution comes from the JWT `sub`, never from the request body.
- **`ON DELETE RESTRICT`** on chunk → document: a document version can't silently disappear.
- **Chunks normalized** into `document_chunks` instead of repeating document metadata per chunk.
- **`VECTOR(768)`** matches `nomic-embed-text`'s real output (Spring AI's docs default to 1536 for OpenAI).
  Changing the embedding model means a migration plus re-embedding everything.
- **Custom `VectorStore` instead of Spring AI's `PgVectorStore`:** its auto-generated single table
  can't express "search only the active version".

`upsert_document(p_name, p_user_id, p_source_path, p_is_new, p_chunks jsonb) → uuid`:

- Validates everything before any write (non-empty name/user/path, non-empty chunk array, text and a
  768-element embedding per chunk); one bad chunk rejects the whole batch.
- `is_new = true` with an active row → `23505`; `is_new = false` with none → `P0002`. The lookup is by
  **name + `is_active`**, never by a client-supplied id (a stale id could corrupt a superseded row).
- One transaction: validate → `SELECT … FOR UPDATE` on the active row → deactivate → insert the new
  document → insert chunks. One `CURRENT_TIMESTAMP` is used for both `effective_to` and `effective_from`.
- A concurrent update blocks on the row lock, then finds no active row (`P0002`). Two simultaneous
  creates of a new name are settled by the partial unique index (re-raised as `23505`).

## Known limitations

- **Tool calling doesn't work.** Spring AI 1.0.0-M1's Ollama client has no tool support
  (`OllamaApi.ChatRequest` has no `tools` field), so `.functions(...)` is silently ignored. Fixing it
  needs a Spring AI upgrade (expect API changes: advisors, `functions()` → `tools()`, starter names);
  re-run the eval afterwards.
- No retries or timeouts on Ollama calls.
- No per-request audit log (question, answer, user, sources).
- JWT: no issuer/audience validation and no roles; keys are local development keys.
- No similarity threshold. The eval showed out-of-scope matches can be closer than in-scope ones, so
  refusal relies on the prompt template.
- Within one `conversationId`, chat history can carry an outdated answer across a document update.
- `created_by` is `VARCHAR(50)`; `embedding` is nullable; no document-type column; nomic
  `search_document:`/`search_query:` prefixes aren't used (adding them needs re-ingestion).
- `TextReader` handles plain text only (no PDF/DOCX).
