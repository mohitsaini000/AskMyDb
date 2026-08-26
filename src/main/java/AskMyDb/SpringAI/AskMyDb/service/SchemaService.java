package AskMyDb.SpringAI.AskMyDb.service;

import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

// Reads the real database structure at request time using plain JDBC metadata APIs
// (not JPA/Hibernate) so it works even though we don't have @Entity classes -
// we don't know the shape of the data ahead of time, the LLM needs to "see" it.
@Service
public class SchemaService {

    private final DataSource dataSource;

    // Tables that belong to AskMyDb's own bookkeeping, not the user's business
    // data. They live in the same "public" schema as everything else, but must
    // never be described to the LLM as queryable, and must never get embedded
    // as if they were a real table - both would be a confusing (and slightly
    // circular) mistake.
    private static final Set<String> INTERNAL_TABLES = Set.of("schema_embeddings", "schema_index_state");

    public SchemaService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // Full schema as one block. Kept for anything that genuinely needs the
    // whole schema at once (e.g. the one-time indexing step reads it table by
    // table instead, but this is handy for debugging/manual inspection).
    public String getSchemaDescription() {
        StringBuilder sb = new StringBuilder();
        for (String description : getTableDescriptions().values()) {
            sb.append(description).append("\n");
        }
        return sb.toString();
    }

    // One description per table, keyed by table name - e.g.
    //   "customers" -> "Table: customers\n  - id (integer)\n  - name (varchar)\n..."
    //
    // This is the shape the RAG layer needs: each table becomes its own
    // embeddable chunk, instead of one giant blob covering the whole schema.
    public Map<String, String> getTableDescriptions() {
        Map<String, String> descriptions = new LinkedHashMap<>();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();

            try (ResultSet tables = metaData.getTables(null, "public", "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    if (INTERNAL_TABLES.contains(tableName)) {
                        continue;
                    }
                    descriptions.put(tableName, describeTable(metaData, tableName));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read database schema", e);
        }

        return descriptions;
    }

    private String describeTable(DatabaseMetaData metaData, String tableName) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("Table: ").append(tableName).append("\n");

        try (ResultSet columns = metaData.getColumns(null, "public", tableName, "%")) {
            while (columns.next()) {
                String columnName = columns.getString("COLUMN_NAME");
                String columnType = columns.getString("TYPE_NAME");
                sb.append("  - ").append(columnName).append(" (").append(columnType).append(")\n");
            }
        }

        appendForeignKeys(metaData, tableName, sb);

        return sb.toString();
    }

    // Spells out exactly which column joins to which table/column, in plain
    // text, right in the schema description the LLM sees - e.g.
    //   "  - order_id references orders(id)"
    // Without this, the model only sees "order_id (integer)" and has to
    // *guess* which table that's a foreign key into, purely from the column
    // name. That guess is exactly how a wrong join like
    // "JOIN customers ON order_items.order_id = customers.id" happens -
    // Postgres has no way to catch it (it's valid SQL, it just silently
    // returns zero/wrong rows), so the mistake has to be prevented up front
    // instead of caught after the fact.
    private void appendForeignKeys(DatabaseMetaData metaData, String tableName, StringBuilder sb) throws SQLException {
        try (ResultSet keys = metaData.getImportedKeys(null, "public", tableName)) {
            boolean headerWritten = false;
            while (keys.next()) {
                String fkColumn = keys.getString("FKCOLUMN_NAME");
                String referencedTable = keys.getString("PKTABLE_NAME");
                String referencedColumn = keys.getString("PKCOLUMN_NAME");

                if (INTERNAL_TABLES.contains(referencedTable)) {
                    continue;
                }

                if (!headerWritten) {
                    sb.append("  Foreign keys:\n");
                    headerWritten = true;
                }
                sb.append("    - ").append(fkColumn)
                        .append(" references ").append(referencedTable)
                        .append("(").append(referencedColumn).append(")\n");
            }
        }
    }

    // "Schema linking" step for the RAG layer: similarity search alone only
    // finds tables whose *description text* resembles the question - it has
    // no idea which tables are actually JOINable to each other. This method
    // finds every table directly foreign-key-connected to the given table,
    // in both directions, so a table that's structurally necessary for a
    // correct JOIN doesn't get silently left out just because its column
    // names didn't happen to match the question's wording.
    public Set<String> getRelatedTables(String tableName) {
        Set<String> related = new LinkedHashSet<>();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();

            // Tables that *this* table has a foreign key pointing to
            // (e.g. orders.customer_id -> customers.id: getRelatedTables("orders")
            // finds "customers" here).
            try (ResultSet outgoing = metaData.getImportedKeys(null, "public", tableName)) {
                while (outgoing.next()) {
                    String referencedTable = outgoing.getString("PKTABLE_NAME");
                    if (!INTERNAL_TABLES.contains(referencedTable)) {
                        related.add(referencedTable);
                    }
                }
            }

            // Tables that have a foreign key pointing *at* this table
            // (e.g. getRelatedTables("customers") finds "orders" here, via
            // orders.customer_id -> customers.id, the other direction).
            try (ResultSet incoming = metaData.getExportedKeys(null, "public", tableName)) {
                while (incoming.next()) {
                    String referencingTable = incoming.getString("FKTABLE_NAME");
                    if (!INTERNAL_TABLES.contains(referencingTable)) {
                        related.add(referencingTable);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read foreign key relationships for table: " + tableName, e);
        }

        return related;
    }

    // A stable "fingerprint" of the current schema's shape (every table's
    // name, columns and column types, combined and hashed). SchemaIndexer
    // compares this against the fingerprint it saved after the last index:
    //   - same hash  -> schema hasn't changed, safe to skip re-indexing.
    //   - different  -> a table/column was added, removed or retyped since
    //                   the embeddings were built, so they're now stale and
    //                   must be rebuilt.
    // TreeMap sorts by table name first so the same schema always hashes to
    // the same value regardless of the order the database happens to return
    // tables in.
    public String computeSchemaFingerprint() {
        Map<String, String> sortedDescriptions = new TreeMap<>(getTableDescriptions());

        StringBuilder combined = new StringBuilder();
        for (String description : sortedDescriptions.values()) {
            combined.append(description);
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(combined.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to exist on every standard JDK - this is
            // effectively unreachable, but the checked exception must be handled.
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
