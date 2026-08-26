package AskMyDb.SpringAI.AskMyDb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Runs once at application startup and embeds a description of every table
// into the PgVectorStore - this is the "R" (retrieval) side of RAG being
// prepared ahead of time, so query time only has to do a fast similarity
// search instead of re-embedding the whole schema on every question.
//
// Idempotent on purpose: re-running the app should not re-embed (and
// duplicate) the same tables every single restart, so it checks first.
@Component
public class SchemaIndexer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaIndexer.class);

    private final SchemaService schemaService;
    private final VectorStore vectorStore;

    public SchemaIndexer(SchemaService schemaService, VectorStore vectorStore) {
        this.schemaService = schemaService;
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {
        if (alreadyIndexed()) {
            log.info("Schema embeddings already present - skipping re-index. " +
                    "(Drop the schema_embeddings table manually if the schema changed and you need a rebuild.)");
            return;
        }

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
        log.info("Indexed {} table(s) into the vector store: {}", documents.size(), tableDescriptions.keySet());
    }

    // Cheap existence check: nothing sophisticated like a flag row - just ask
    // the vector store for anything at all and see if it has content yet.
    private boolean alreadyIndexed() {
        List<Document> existing = vectorStore.similaritySearch(
                SearchRequest.builder().query("table").topK(1).build());
        return !existing.isEmpty();
    }

    // Name-based (v3) UUID: deterministic, so the same table name always
    // produces the same id - re-indexing later would update the existing
    // row for that table instead of creating a duplicate with a new random id.
    private static String tableNameToId(String tableName) {
        return UUID.nameUUIDFromBytes(("askmydb-table-" + tableName).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
