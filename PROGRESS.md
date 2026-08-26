# AskMyDb — Progress Log

A Spring Boot + Spring AI project that answers plain-English questions about a
database by generating SQL with a local LLM (Ollama), validating it through a
safety layer, executing it for real, and returning actual data.

---

## Day 1 — 2026-08-25

### Concept

The core idea is **grounding**: the LLM never answers a question directly from
its own memory (which would risk hallucinated numbers). Instead, the LLM's
only job is to translate a natural-language question into SQL. That SQL is
then validated and executed against the real database, so the actual answer
always comes from real data, not from the model's guess.

### Environment

- **Postgres + pgvector** via Docker Compose (`docker-compose.yml`), running
  on port `5434` on the host to avoid clashing with other local Postgres
  instances already using `5432` and `5433`.
- **Ollama**, running locally, with two models:
  - `llama3.2` — the chat model used to generate SQL
  - `nomic-embed-text` — an embedding model, not used yet, reserved for a
    future RAG (retrieval-augmented) layer over the schema

### Spring AI wiring

- Added `spring-ai-starter-model-ollama` to `pom.xml`.
- Configured `spring.ai.ollama.*` in `application.yaml`, including
  `temperature: 0.1` — kept low deliberately, since SQL generation needs to
  be precise and repeatable, not creative.
- `config/AiConfig.java` exposes a shared `ChatClient` bean built from the
  auto-configured `ChatClient.Builder`.
- `controller/AiTestController.java` (`/api/test-ai`) — a minimal endpoint
  used only to confirm the Spring Boot ↔ Ollama connection works before
  building anything else on top of it.

### Security (temporary)

`config/SecurityConfig.java` currently permits all requests and disables the
default login form. This is explicitly a placeholder — the plan is to replace
it with real JWT-based authentication later.

### Sample dataset

`scripts/sample-data.sql` creates a small e-commerce schema — `customers`,
`products`, `orders`, `order_items` — with realistic seed data, used to test
natural-language questions end to end.

### The NL-to-SQL pipeline

- `service/SchemaService.java` — reads the live database schema via JDBC
  `DatabaseMetaData` (not hardcoded), so the prompt always reflects the real
  table/column structure.
- `service/NlToSqlService.java` — combines the schema description with the
  user's question into a prompt, calls the `ChatClient`, and cleans the
  response (stripping markdown fences the model sometimes adds despite being
  told not to). The prompt uses:
  - A `system` message (strict rules) separate from the `user` message (the
    actual question) — models follow system instructions more reliably.
  - An explicit rule against inventing columns, added after the model
    hallucinated a `total_amount` column that didn't exist in the schema.
  - A one-shot example (few-shot prompting) showing how to derive a value
    that has no direct column (e.g. computing revenue via `SUM(quantity *
    unit_price)`), which measurably improved accuracy on a small local model.
  - A `CANNOT_ANSWER` escape hatch for questions the schema can't support.
- `service/SqlGuardrail.java` — the safety layer between "text an LLM
  produced" and "SQL that touches the real database". It:
  - Rejects anything that isn't a `SELECT` statement
  - Blocks destructive keywords (INSERT/UPDATE/DELETE/DROP/ALTER/etc.),
    matched as whole words so a legitimate column like `updated_at` isn't a
    false positive
  - Blocks multiple statements (`;`-separated SQL injection)
  - Auto-appends a `LIMIT` if the model didn't add one
- `service/QueryExecutionService.java` — executes already-validated SQL via
  `JdbcTemplate` (not JPA, since the result shape isn't known ahead of time).
- `service/AskService.java` — orchestrates the full flow: question → generated
  SQL → validated SQL → real rows.
- `controller/AskController.java` — `POST /api/ask`, taking a JSON
  `AskRequest` (validated with `@NotBlank`/`@Size` so empty or oversized
  questions never reach the LLM) and returning an `AskResponse` with the
  question, the exact SQL that ran (for transparency), and the rows. Errors
  are caught and turned into clean `ProblemDetail` (RFC 7807) responses
  instead of stack traces — both guardrail rejections and real Postgres
  errors (e.g. a bad column reference) are handled gracefully.

### Debugging lessons worth remembering

- **Docker port mapping** (`"host:container"`) — the container's internal
  port for Postgres is always `5432` regardless of what host port it's
  mapped to; multiple projects on one machine need distinct host ports.
- **External file edits vs. an open IDE buffer** — editing a file outside
  IntelliJ while it's open there can get silently reverted if IntelliJ
  re-saves its stale in-memory copy. Reloading the file (or closing/
  reopening it) fixes this.
- **Java code changes need an actual restart, not a browser refresh** —
  unlike config files, `.java` changes require stopping and re-running the
  app to recompile.
- **Docker named volumes persist across `down`/`up`** — environment
  variables like `POSTGRES_DB` only take effect on a truly fresh data
  directory. An already-initialized volume ignores them on subsequent
  starts.
- **A Postgres server has multiple databases, and each database has
  multiple schemas** — these are two different levels. A table can exist in
  the wrong *database* even if its *schema* name looks right, which is
  exactly what happened here (tables ended up in `postgres` database, in a
  schema literally named `askmydb`, instead of in the `askmydb` database's
  `public` schema).
- **Hallucination is real and prompt engineering alone doesn't eliminate
  it** — strict rules and a few-shot example measurably reduced errors but
  didn't guarantee correctness, which is exactly why the guardrail +
  execution-time error handling layer exists as a second line of defense.

### Tooling

- `postman/AskMyDb.postman_collection.json` — a Postman collection covering
  diagnostics, SQL-only generation, and the full `/api/ask` pipeline
  (including a validation-failure case and a guardrail/destructive-request
  case).
- GitHub repo created at `github.com/mohitsaini000/AskMyDb` (not yet pushed - local commits still pending).

---

## Day 2 — 2026-08-26

### Task 7: edge-case testing surfaced two more hallucination classes

Ran a batch of edge-case questions against the full `/api/ask` pipeline, on
top of the earlier `total_amount` hallucination fix:

- **Tie-handling bug.** "Which cities has the most customers if more than 1
  city exist then give me all those cities those are equal no of count?"
  The model's first answer used `ORDER BY COUNT(*) DESC LIMIT 1`, which
  silently drops a real tie (Bengaluru and Mumbai were actually tied in the
  seed data — `LIMIT 1` just hid Mumbai). Fixed with a second few-shot
  example showing the correct pattern for tie-sensitive questions:
  `HAVING COUNT(*) = (SELECT MAX(...) FROM (...))` instead of `LIMIT 1`.
  Re-tested — both tied cities now come back.

- **Off-topic hallucination.** "What's today's weather in Bengaluru?" should
  have produced `CANNOT_ANSWER`, but the model invented a nonexistent
  `cities` table instead, so it only failed at Postgres execution time
  (`422`, "relation \"cities\" does not exist"). Not unsafe — the
  guardrail-then-database layer caught it exactly as designed, nothing ran
  against real data — but not the cleanest message either. Fixed two ways:
  strengthened the "never invent names" rule to explicitly warn against
  inventing a table/column just because a word in the question resembles
  one, and added a third few-shot example (the weather question →
  `CANNOT_ANSWER`). Re-tested — now returns a clean `400` with "This
  question can't be answered with the current database schema."

- Also verified the ambiguous case "Who are my best customers?" was already
  handled well: the model interpreted "best" as total spend and derived it
  correctly via `JOIN` + `SUM`, with no invented columns.

This makes three times now that a concrete few-shot example fixed a class of
hallucination that a plain written rule alone didn't fully fix — worth
remembering as a general lesson for a small local model: showing the correct
SQL *shape* works better than just describing the rule in prose.

### Frontend: a proper demo UI

Built in two passes:

1. **First pass** — `src/main/resources/static/index.html`, a plain
   functional page: question input, generated SQL, a results table, and
   error states. Spring Boot serves anything under `static/` automatically,
   and because the page and `/api/ask` are same-origin, there's no CORS to
   configure — one of the reasons a separate frontend project (React on its
   own dev server) was deliberately not used.
2. **Second pass**, rebuilt as a 4-section animated single page (hero, live
   console, "how it works", footer):
   - **Hero** — an ambient Canvas animation (a slowly drifting node/edge
     network, echoing "related tables") behind a looping simulated demo
     (typed question → generated SQL → result), built from the real sample
     data, so a visitor understands the product before touching anything.
   - **Console** — the real, functional `/api/ask` UI: a 3-stage visual
     indicator ("Drafting SQL" / "Checking safety" / "Querying Postgres")
     during the wait, a copy-to-clipboard button on the generated SQL, and
     staggered row animations on the results table.
   - **How it works** — a scroll-revealed 4-step timeline mirroring the
     actual pipeline, with the one AI-touching step called out in amber —
     the same color coding used in the request-flow diagram, so the two
     artifacts read as one system.
   - **Footer** — tech stack badges and the repo link.
   - Still deliberately vanilla HTML/CSS/JS, no build step or framework —
     same reasoning as pass 1: this project's differentiator is the
     backend, so the frontend only needs to demo it well, not show off a
     separate skill set.

Confirmed end to end in the browser: asked "which customer give us highest
revenue" through the real console and got a correctly-derived SQL (`JOIN` +
`SUM`, no invented columns) with the right top result (Priya Verma).

---

## Next up

- pgvector-based RAG layer: embed schema descriptions with `nomic-embed-text`
  and retrieve only relevant tables for large schemas, instead of stuffing
  the whole schema into every prompt.
- Real authentication (JWT) to replace the temporary open `SecurityConfig`.
- Confirm the final `git push` to GitHub succeeded (Credential Manager
  account mismatch was being fixed).
- Optional stretch goals discussed: self-correcting SQL (feed a Postgres
  error back to the LLM to retry), an MCP server mode so external AI clients
  (e.g. Claude Desktop) can query the database directly, and a pluggable
  multi-provider AI setup (Strategy pattern over multiple `ChatClient`
  beans).
