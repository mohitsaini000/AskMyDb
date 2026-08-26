package AskMyDb.SpringAI.AskMyDb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Runs once at every application startup and embeds a description of every
// table into the PgVectorStore - this is the "R" (retrieval) side of RAG
// being prepared ahead of time, so query time only has to do a fast
// similarity search instead of re-embedding the whole schema on every question.
//
// Idempotent AND schema-drift-aware: re-running the app should not re-embed
// the same tables every restart (wasted work, and Ollama calls aren't free),
// but if the schema actually changed since the last index - a table or
// column was added, removed or retyped - the old embeddings describe a
// schema that no longer exists. Left alone, that's a silent correctness bug:
// the LLM would keep being shown a stale/wrong schema forever, with nothing
// in the logs or the API response hinting that anything was wrong.
@Component
public class SchemaIndexer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaIndexer.class);

    // A single-row table (id is always 1) - there's only ever "the schema
    // fingerprint from the last successful index" to remember, not a history
    // of every past one.
    private static final String ENSURE_STATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS schema_index_state (
                id INT PRIMARY KEY,
                fingerprint TEXT NOT NULL,
                indexed_at TIMESTAMP NOT NULL
            )
            """;

    private final SchemaService schemaService;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    // Matches spring.ai.vectorstore.pgvector.table-name in application.yaml -
    // read from the same property (instead of a second hardcoded literal)
    // so the two can never silently drift apart.
    private final String vectorTableName;

    public SchemaIndexer(SchemaService schemaService,
                          VectorStore vectorStore,
                          JdbcTemplate jdbcTemplate,
                          @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String vectorTableName) {
        this.schemaService = schemaService;
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.vectorTableName = vectorTableName;
    }

    @Override
    public void run(String... args) {
        // Needed by SchemaService.getSampleValues()' fuzzy-matching path
        // (similarity() function) for value linking on large columns - a
        // one-time, idempotent setup step, same idea as the state table
        // below.
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");

        jdbcTemplate.execute(ENSURE_STATE_TABLE_SQL);

        String currentFingerprint = schemaService.computeSchemaFingerprint();
        Optional<String> storedFingerprint = readStoredFingerprint();

        if (storedFingerprint.isPresent() && storedFingerprint.get().equals(currentFingerprint)) {
            log.info("Schema unchanged since last index - skipping re-index.");
            return;
        }

        if (storedFingerprint.isPresent()) {
            log.info("Schema change detected since last index (tables/columns added, removed or retyped) - rebuilding embeddings.");
        } else {
            log.info("No previous index found - building schema embeddings for the first time.");
        }

        reindex(currentFingerprint);
    }

    private void reindex(String fingerprint) {
        // Wipe every previously-embedded table description before re-adding
        // the current ones. A delete-then-insert (rather than a per-table
        // diff) is simplest and correct even when a table was renamed or
        // dropped entirely - a stale embedding for a table that no longer
        // exists would otherwise keep getting recommended to the LLM forever.
        jdbcTemplate.update("DELETE FROM " + vectorTableName);

        Map<String, String> tableDescriptions = schemaService.getTableDescriptions();

        List<Document> documents = tableDescriptions.entrySet().stream()
                .map(entry -> new Document(
                        tableNameToId(entry.getKey()),               // PgVectorStore's id column is a UUID -
                                                                      // a plain table name like "customers"
                                                                      // isn't valid there, so derive a stable
                                                                      // UUID from the name instead (same table
                                                                      // name always maps to the same UUID).
                        entry.getValue(),                            // the table's description text
                        Map.of("table", entry.getKey())))            // metadata, useful for debugging
                .toList();

        vectorStore.add(documents);
        saveFingerprint(fingerprint);

        log.info("Indexed {} table(s) into the vector store: {}", documents.size(), tableDescriptions.keySet());
    }

    private Optional<String> readStoredFingerprint() {
        List<String> rows = jdbcTemplate.query(
                "SELECT fingerprint FROM schema_index_state WHERE id = 1",
                (rs, rowNum) -> rs.getString("fingerprint"));
        return rows.stream().findFirst();
    }

    private void saveFingerprint(String fingerprint) {
        jdbcTemplate.update("""
                INSERT INTO schema_index_state (id, fingerprint, indexed_at)
                VALUES (1, ?, ?)
                ON CONFLICT (id) DO UPDATE SET fingerprint = EXCLUDED.fingerprint, indexed_at = EXCLUDED.indexed_at
                """,
                fingerprint, Timestamp.from(Instant.now()));
    }

    // Name-based (v3) UUID: deterministic, so the same table name always
    // produces the same id - re-indexing later would update/replace the
    // existing row for that table instead of creating a duplicate with a
    // new random id.
    private static String tableNameToId(String tableName) {
        return UUID.nameUUIDFromBytes(("askmydb-table-" + tableName).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
