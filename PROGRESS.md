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

## Day 3 — 2026-08-26

### Git housekeeping

Committed the frontend rebuild and the two prompt fixes from Day 2
(commit `47d1731`), after clearing a stale `.git/index.lock` left over from
an interrupted process. `git log` confirms the earlier Credential Manager
fix actually worked — the branch was already in sync with `origin/main`
before this commit, just missing the local push for the newest work.

### RAG layer: retrieval instead of a full schema dump

Replaced "stuff the entire schema into every prompt" with a real
retrieval-augmented pipeline, using the `pgvector`/`nomic-embed-text`
infrastructure that had been sitting configured-but-unused since Day 1.

- **Config** — added `spring.ai.vectorstore.pgvector.*` to
  `application.yaml`: `initialize-schema: true` (Spring Boot creates the
  `vector` extension and the table automatically), `dimensions: 768`
  (must match `nomic-embed-text`'s actual output size, or inserts fail),
  and a custom `table-name: schema_embeddings`.
- **`SchemaService`** — added `getTableDescriptions()`, returning one
  description per table instead of one giant string, so each table can
  become its own embeddable chunk. Also excludes the `schema_embeddings`
  table itself from every listing, so the RAG layer never tries to
  describe (or embed) its own storage table.
- **`SchemaIndexer`** (new, `CommandLineRunner`) — runs once at startup,
  embeds each table's description via the vector store, and stores it.
  Idempotent: checks whether the store already has content before
  re-indexing, so restarts don't create duplicates. Table names are
  converted to deterministic UUIDs (`UUID.nameUUIDFromBytes`) before being
  used as document IDs, since `PgVectorStore`'s `id` column requires valid
  UUIDs — this was the first bug hit (`Invalid UUID string: customers`),
  fixed by hashing the name into a stable UUID rather than using it raw.
- **`NlToSqlService`** — `getSchemaDescription()` (the full dump) was
  replaced with a `retrieveRelevantSchema(question)` step: embed the
  question, similarity-search the vector store for the top-K (configurable
  via `askmydb.rag.top-k`, default 3) most relevant tables, and build the
  prompt's schema section from only those.

### The bug retrieval alone can't avoid — and the fix

Top-K-by-similarity has a real gap: it finds tables whose *wording*
resembles the question, but has no concept of which tables are actually
JOINable. Caught this directly: "Which product category made the most
revenue from customers in Bengaluru?" needs all four tables, but with
`top-k: 3`, `order_items` didn't make the cut. The model didn't refuse —
it forced a join between the three tables it *did* see
(`JOIN products p ON o.id = p.id`, matching two unrelated ID columns that
happen to both be small integers). This ran without error and returned a
confident, wrong answer ("Stationery") — the dangerous class of failure,
since nothing about the response looks off.

Fixed with a "schema linking" expansion step, `SchemaService.
getRelatedTables(tableName)`: after the similarity search returns its
top-K seed tables, look up every table each one is directly foreign-key
connected to (via JDBC `getImportedKeys`/`getExportedKeys`, both
directions) and add those too, even if they scored low on their own. For
the same question, `order_items` now gets pulled in because it's an FK
neighbor of `orders`, and the generated SQL changed to correctly join
`products → order_items → orders → customers` with a proper
`SUM(oi.quantity * oi.unit_price)` — and the answer changed to
"Furniture", confirming the first answer wasn't just ugly SQL, it was
factually wrong.

This is worth remembering as a general lesson: semantic similarity finds
*relevant* tables, but relevance and *join-reachability* are different
things, and only the second one determines whether a query can even be
constructed correctly.

---

## Day 4 — 2026-08-26

Pushed the two pending commits from Day 3 to `origin/main` — confirmed
`git log origin/main` matches local `main`.

Closed a real gap flagged in the RAG writeup: `SchemaIndexer` used to
decide whether to (re-)index with a cheap existence check — "is anything
at all in the vector store?" That means if the schema changes later (a
column added, a table dropped) *without* the `schema_embeddings` table
being manually cleared first, the app would see it's non-empty, assume
everything's fine, and keep serving stale table descriptions to the LLM
forever, with nothing in the logs or the API hinting anything was wrong.

Replaced it with a fingerprint-based check. `SchemaService
.computeSchemaFingerprint()` builds a deterministic string from every
table's name, columns and column types (sorted first, so ordering from
the database driver can't change the hash), then SHA-256-hashes it into
one short string that changes if and only if the schema's shape changes.

`SchemaIndexer` now:
1. Ensures a tiny `schema_index_state` table exists (one row, id = 1) to
   remember the fingerprint from the last successful index.
2. Computes the *current* live fingerprint on every startup.
3. Compares it to the stored one:
   - same → schema hasn't moved, skip re-indexing (same fast-startup
     behavior as before).
   - different (or no row yet) → logs why, deletes every row from the
     vector table, re-embeds every current table from scratch, and saves
     the new fingerprint.

Deliberately a full delete-and-rebuild rather than a per-table diff: it's
simpler, and it's the only version that's automatically correct when a
table is *renamed or dropped* — a diff that only adds/updates changed
tables would leave a stale, no-longer-real table's embedding sitting in
the vector store forever, still retrievable by similarity search.

`schema_index_state` was added to `SchemaService.INTERNAL_TABLES` for the
same reason `schema_embeddings` already was — it's AskMyDb's own
bookkeeping, not a business table, so it must never be described to the
LLM or embedded as if it were queryable data.

---

## Day 5 — 2026-08-26

Investigated a real user complaint: "answers are taking too long." Added
temporary timing logs around each stage (schema retrieval, LLM call,
guardrail, DB execute) instead of guessing where the time went. The
numbers were clear: schema retrieval + guardrail + DB execute together
took ~200-300ms; the LLM call itself took 4.7-10.5 seconds - 85-95% of
total request time. This is a local Ollama model generating text on a
CPU-only machine, not a code inefficiency. Documented as a known,
hardware-bound trade-off (options: smaller model, GPU, or a hosted API -
each with its own cost/latency/privacy trade-off) rather than something to
"fix" in application code.

While instrumenting, live-tested a multi-table question ("customers in a
city + the products they bought") and found a second real bug: the
generated SQL joined `order_items.order_id` directly to `customers.id`,
skipping the `orders` table entirely. Valid SQL, wrong join - Postgres
has no way to flag this, so it silently returned 0 rows instead of an
error, the same dangerous failure class as the earlier FK-linking bug.

Fixed this in three iterations, keeping only what was actually proven to
help:

1. **Self-correcting SQL retry** (`AskService`, `NlToSqlService
   .regenerateSql()`): when `QueryExecutionService.execute()` throws a
   `DataAccessException`, the exact Postgres error message is fed back to
   the LLM with the failed SQL and one retry is allowed (re-validated
   through the same `SqlGuardrail` as any other generated SQL - a retry
   doesn't skip safety checks). This fixed a *different* bug it surfaced
   along the way (a stray `t2.name` reference to a non-existent column)
   but did NOT fix the join bug, because a wrong-but-valid join never
   throws a database error in the first place - there's nothing for
   Postgres to report back. Real lesson: self-correction only catches
   what the database itself can detect; a semantically wrong join is
   invisible to it.
2. **Explicit foreign keys in the schema text** (`SchemaService
   .appendForeignKeys()`): table descriptions now spell out
   `- order_id references orders(id)` using the same JDBC
   `getImportedKeys()` data `getRelatedTables()` already reads, instead of
   just listing column name + type. Necessary, but not sufficient on its
   own - the model still produced the same wrong join afterward.
3. **A fourth few-shot example** demonstrating exactly this shape of
   question - joining through two intermediate "bridge" tables
   (`orders`, `order_items`) between `customers` and `products` - is what
   actually fixed it. Re-running the identical question afterward produced
   the correct 4-table join.

That fix then surfaced a *third*, genuinely different issue: the corrected
query (`WHERE c.city = 'Bangalore'`) still returned 0 rows, even though
the join was now correct. Real data uses "Bengaluru", not "Bangalore" -
the LLM normalized the user's Hinglish "banglore" to the common English
spelling, which doesn't match the stored value. Re-running with
"Bengaluru" returned the correct 3 rows. This isn't a join/schema bug at
all - it's a distinct, well-known NL-to-SQL problem called *value
linking* (matching a mentioned entity to the literal value actually
stored in the database, as opposed to *schema linking*, which matches it
to the right table/column). Left unfixed for now and noted as a known
gap - a real fix would mean fuzzy-matching literals (`ILIKE`) or
maintaining a small alias dictionary for common name variants.

---

## Day 6 — 2026-08-30

Three separate pieces of hardening, none blocking on each other:

**Value linking, upgraded to scale.** The Day 5 fix (dump all distinct
values into the prompt when a column is small) doesn't scale - a column
with hundreds or thousands of distinct values would blow up the prompt
size and cost. `SchemaService.getSampleValues()` now branches on
cardinality: columns with 20 or fewer distinct values still get the full
list (cheap and always accurate), but larger columns fall back to
`fetchFuzzyMatches()`, which uses Postgres's `pg_trgm` extension
(trigram/character-overlap similarity, enabled once via
`CREATE EXTENSION IF NOT EXISTS pg_trgm` in `SchemaIndexer`) to pull only
the top 5 values whose `similarity()` to the user's question exceeds a
threshold. This is pure SQL running inside Postgres - no embeddings, no
extra AI call - and catches typos/near-matches, though it still won't
catch a true alias with no character overlap (e.g. "Bombay" vs "Mumbai").

**Exception handling, consolidated.** All custom exceptions
(`UnsafeSqlException`, `UsernameTakenException`, now also
`InvalidCredentialsException`) moved into one `exception` package, and a
`GlobalExceptionHandler` (`@RestControllerAdvice`) replaced the
per-controller `@ExceptionHandler` methods that had been duplicated
across `AskController` and `AuthController`. One place now maps every
failure type to its HTTP status (400/401/409/422/500), with a catch-all
`Exception` handler that logs full details server-side but only ever
returns a generic message to the client - standard practice for not
leaking internals.

**JWT authentication, Stage B.** Stage A (Day earlier) added registration
with BCrypt-hashed passwords. Stage B adds `POST /api/auth/login`:
`AuthService.login()` looks up the user, checks the raw password against
the stored hash with `PasswordEncoder.matches()`, and on success calls the
new `JwtService.generateToken()` to mint a signed JWT (JJWT 0.13.0,
HMAC-SHA256, secret + expiry from `application.yaml`). Deliberately
returns the exact same `InvalidCredentialsException` whether the username
doesn't exist or the password is wrong, so a client can't use the error
to enumerate valid usernames. `SecurityConfig` still `permitAll()`s
everything for now - nothing reads or checks the token on protected
requests yet. That's Stage C: a `JwtAuthFilter` that verifies the
signature and expiry on every request and rejects ones without a valid
token.

---

## Next up

- JWT authentication, Stage C: a `JwtAuthFilter` that verifies the token
  signature/expiry on protected requests and actually enforces it in
  `SecurityConfig` (registration and login stay public; everything else
  should require a valid token).
- Value linking: the pg_trgm fuzzy match (Day 6) catches typos/near
  matches but not true aliases with no character overlap (e.g. "Bombay"
  vs "Mumbai") - would need a small alias dictionary or a different
  technique to close that gap.
- Multi-hop schema linking: the current FK expansion is one level deep,
  which is enough for this schema's simple chain but wouldn't guarantee
  correctness on a schema where the needed table is two FK-hops away from
  every seed match.
- Optional stretch goals discussed: an MCP server mode so external AI
  clients (e.g. Claude Desktop) can query the database directly, and a
  pluggable multi-provider AI setup (Strategy pattern over multiple
  `ChatClient` beans).
