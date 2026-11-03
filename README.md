# Versioned Policy Assistant (RAG proof of concept)

A Spring Boot service that answers questions about policy documents using retrieval-augmented
generation (RAG), and shows which document **and version** each answer was based on. Documents are
versioned: uploading a new version supersedes the old one without deleting it, so the full history is
kept and retrieval only ever searches the version currently in force.

Everything runs locally. The models run in [Ollama](https://ollama.com) and the data lives in Postgres
with pgvector, so no document or question leaves the machine.

The sample policies in `demo/documents/` are **fictional**, written for this project.

## Highlights

- **Answers with sources.** Every response lists the passages that were put in the model's prompt,
  with document name, version, effective date and match distance.
- **Version history built into the schema.** One row per version, at most one active per document
  (enforced by a partial unique index), effective dates that tile with no gap or overlap, and
  `ON DELETE RESTRICT` so a version can't silently disappear.
- **Atomic version swap.** A PL/pgSQL function validates the whole upload, locks the active version
  (`SELECT … FOR UPDATE`), deactivates it and inserts the new version with its chunks, all in one transaction.
- **Authenticated and attributable.** JWT bearer tokens (RS256); the uploader recorded in the audit
  columns comes from the token, never from the request.
- **Tested against real infrastructure.** 55 tests, including concurrency tests, run against a real
  pgvector database in Testcontainers. A 15-question evaluation set checks answers and sources.

## Architecture

```
Upload   POST /api/v1/documents (multipart, JWT)
           → save original file → read + split into chunks → embed (nomic-embed-text, 768-d)
           → upsert_document(): validate → lock active version → deactivate → insert version + chunks

Ask      POST /api/v1/chat (JSON, JWT)
           → embed question → 3 nearest chunks by cosine distance, active versions only (pgvector)
           → chunks added to the prompt → answer (qwen2.5-coder) → { answer, sources[] }
```

| Layer | Technology |
|---|---|
| Service | Java 21, Spring Boot 3.3, Spring AI 1.0.0-M1 |
| Models | Ollama: `qwen2.5-coder` (chat, temperature 0), `nomic-embed-text` (embeddings) |
| Storage | Postgres 16 + pgvector (HNSW index, cosine), Flyway migrations |
| Security | Spring Security OAuth2 resource server, RS256 JWT |
| Packaging | Multi-stage Docker build (JRE-only runtime image), Docker Compose for Postgres |
| Tests | JUnit 5, Testcontainers, MockMvc, spring-security-test |

## Quick start

**Prerequisites:** Docker, JDK 21 + Maven, Ollama, and a Bash shell (Git Bash on Windows) with `openssl`.

```bash
# 1. Models and database
ollama pull qwen2.5-coder
ollama pull nomic-embed-text
docker compose up -d                       # Postgres + pgvector on localhost:5433

# 2. Signing keys and a token (first run creates dev-keys/, which is git-ignored)
export TOKEN=$(scripts/dev-token.sh alice)

# 3. Start the service on :8080 (Flyway creates the schema on first start)
mvn spring-boot:run
```

In another terminal (re-export `TOKEN` there):

```bash
# Load the sample policies, then ask a question
demo/load-documents.sh
curl -s -X POST localhost:8080/api/v1/chat \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"prompt":"When are three written quotes required for a purchase?"}'

# Supersede the procurement policy with version 2 and ask again
demo/upload-v2.sh
```

The first question after Ollama loads the model can take close to a minute; later ones take seconds.

### Run in Docker

```bash
docker build -t policy-assistant .
docker run --rm -p 8080:8080 \
  -e POSTGRES_URL=jdbc:postgresql://host.docker.internal:5433/jtms \
  -e HOME_MACHINE_ENDPOINT=http://host.docker.internal:11434 \
  -e JWT_PUBLIC_KEY_LOCATION=file:/keys/public.pem \
  -v "$(pwd -W)/dev-keys/public.pem:/keys/public.pem:ro" \
  policy-assistant
```

(`pwd -W` gives the Windows form of the path in Git Bash; use `$(pwd)` on Linux/macOS.)

## API

All endpoints require `Authorization: Bearer <JWT>`. Errors use RFC 7807 problem JSON.

### `POST /api/v1/documents`: upload a document or a new version

Multipart form: `file` (plain text), `name` (document name), `isNew` (`true` for a new document,
`false` to supersede the active version).

```json
{ "documentId": "4f71a9a1-0447-4089-bfb5-8e4a101aa1a1", "name": "procurement-policy", "chunkCount": 2 }
```

| Status | When |
|---|---|
| 400 | Invalid input (empty name or file, malformed chunks) |
| 401 | Missing, invalid or expired token |
| 404 | `isNew=false` but no active document with that name |
| 409 | `isNew=true` but the name already exists |

### `POST /api/v1/chat`: ask a question

```json
{ "prompt": "When are three written quotes required for a purchase?", "conversationId": "optional" }
```

```json
{
  "answer": "According to the policy, any purchase over $25,000 requires at least three written quotes from independent vendors.",
  "sources": [
    { "documentName": "procurement-policy", "documentVersion": 2,
      "documentId": "4f71a9a1-…", "effectiveFrom": "2026-11-03T23:02:26.385541Z",
      "chunkId": "…", "distance": 0.365 }
  ]
}
```

Without a `conversationId`, each question is answered independently. With one, earlier turns of that
conversation are included; history is scoped to the authenticated user.

### Inspecting version history

```bash
docker exec -i jtms-postgres psql -U jtms -d jtms < demo/queries.sql
```

Shows each version's `is_active`, `created_by`, `effective_from`/`effective_to` and `deactivated_by`,
plus a query for "which version was in force at a given time".

## Tests and evaluation

```bash
mvn test        # needs Docker running (Testcontainers starts pgvector)
```

The evaluation set (`eval/EVAL_SET.md`) has 15 questions over the sample policies, with the answer
key written before any run, including questions where the superseded version's answer would be wrong.

```bash
TOKEN=$(scripts/dev-token.sh eval) eval/run-eval.sh    # saves raw responses to eval/results/
```

Latest result: **15 of 15** on a single run (`eval/RESULTS.md`). It's a regression check over a
small corpus, not a benchmark.

## Project layout

```
src/main/java/example/ai/
  config/       SecurityConfig (JWT), AiConfig (purchase-order tool stub)
  controller/   chat and document endpoints, global error handling
  service/      chat (RAG + memory), document ingestion
  vector/       JtmsVectorStore: retrieval over the custom schema
  model/        request/response records
src/main/resources/db/migration/   V1 schema, V2 upsert_document function
demo/          sample policies and upload scripts
eval/          evaluation set, runner and results
scripts/       dev-token.sh (local signing keys and tokens)
```

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `POSTGRES_URL` | `jdbc:postgresql://localhost:5433/jtms` | Database |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `jtms` / `jtms_dev_password` | Local development credentials only |
| `HOME_MACHINE_ENDPOINT` | `http://localhost:11434` | Ollama |
| `OLLAMA_CHAT_TEMPERATURE` | `0.0` | Chat sampling temperature |
| `JWT_PUBLIC_KEY_LOCATION` | `file:./dev-keys/public.pem` | Public key used to verify tokens |
| `JTMS_DOCUMENTS_DIR` | `./data/documents` | Where uploaded originals are stored |

## Known limitations

- **Tool calling isn't functional.** A purchase-order status tool (a stand-in for an ERP lookup) is
  registered, but Spring AI 1.0.0-M1's Ollama integration doesn't pass tools to the model. Enabling it
  requires a Spring AI upgrade.
- No retries or timeouts on model calls; no per-request audit log of questions and answers.
- Tokens are verified against a local development key; there's no identity provider, issuer/audience
  validation or role-based access yet.
- Plain-text documents only. Chat memory is in-memory.
