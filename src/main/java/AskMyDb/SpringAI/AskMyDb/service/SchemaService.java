package AskMyDb.SpringAI.AskMyDb.service;

import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

// Reads the real database structure at request time using plain JDBC metadata APIs
// (not JPA/Hibernate) so it works even though we don't have @Entity classes -
// we don't know the shape of the data ahead of time, the LLM needs to "see" it.
@Service
public class SchemaService {

    private final DataSource dataSource;

    // The RAG layer's own storage table lives in this same "public" schema.
    // It must never be described to the LLM as a queryable business table,
    // and it must never get embedded as if it were one - both would be a
    // confusing (and slightly circular) mistake.
    private static final Set<String> INTERNAL_TABLES = Set.of("schema_embeddings");

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

        return sb.toString();
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
}
