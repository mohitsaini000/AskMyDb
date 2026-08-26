package AskMyDb.SpringAI.AskMyDb.service;

import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

    // How many distinct values a column can have before we treat it as
    // high-cardinality (800 cities, free-text names, etc.) rather than a
    // small, fully-enumerable categorical column (a handful of statuses or
    // cities). Enforced via LIMIT in the query itself - if the result hits
    // exactly this cap, we don't actually know the true count, so we treat
    // it as "too high" rather than risk dumping hundreds of values.
    private static final int SAMPLE_VALUE_LIMIT = 20;

    // Minimum pg_trgm similarity score (0.0-1.0) for a real stored value to
    // even be considered a match for something in the question. Kept
    // deliberately low - we'd rather show a slightly-too-generous handful
    // of candidates than silently show nothing.
    private static final double SIMILARITY_THRESHOLD = 0.2;

    // How many fuzzy matches to show per high-cardinality column. Small on
    // purpose - this is meant to be a short, targeted hint list, not a
    // second copy of the whole column.
    private static final int FUZZY_MATCH_LIMIT = 5;

    // Live, per-question lookup - deliberately NOT cached, NOT part of
    // getTableDescriptions(), and NOT part of the schema fingerprint. Actual
    // row *values* change far more often than table *structure* does (new
    // customers sign up constantly; a new column doesn't appear often), so
    // baking this into the same cached/embedded text as the structural
    // schema would mean it goes stale almost immediately.
    //
    // This exists to solve "value linking": a user might refer to a real
    // stored value using slightly different wording than what's actually in
    // the database. No amount of schema/column description fixes that - the
    // LLM (or, for large columns, Postgres itself via fuzzy matching) needs
    // to see the *actual* values that exist.
    //
    // Two different strategies depending on scale:
    //   - Small column (<= SAMPLE_VALUE_LIMIT distinct values, e.g. a
    //     handful of cities or statuses): show every value that exists -
    //     cheap, and gives the LLM the complete picture.
    //   - Large column (hundreds/thousands of distinct values, e.g. 800
    //     cities): showing everything doesn't scale and bloats the prompt,
    //     so instead we fuzzy-match the question itself against the real
    //     values using Postgres' pg_trgm extension and only show the
    //     handful of closest matches. Note: this catches genuine
    //     misspellings (e.g. "Bangalor" missing a letter) well, because
    //     those share most of their characters with the real value. It
    //     does NOT reliably catch a true alias/synonym for the same thing
    //     (e.g. "Bangalore" vs "Bengaluru" are different words entirely,
    //     not a typo of each other) - that's a separate problem that would
    //     need a curated synonym mapping, not string similarity.
    public Map<String, List<String>> getSampleValues(String tableName, String question) {
        Map<String, List<String>> samples = new LinkedHashMap<>();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();

            try (ResultSet columns = metaData.getColumns(null, "public", tableName, "%")) {
                while (columns.next()) {
                    String columnName = columns.getString("COLUMN_NAME");
                    String columnType = columns.getString("TYPE_NAME");

                    // Only text-like columns have "spelling variant"
                    // ambiguity the way city/category/status names do -
                    // numbers, dates and ids don't need this treatment.
                    if (!isTextType(columnType)) {
                        continue;
                    }

                    List<String> preview = fetchDistinctValues(connection, tableName, columnName, SAMPLE_VALUE_LIMIT + 1);

                    List<String> values;
                    if (preview.size() <= SAMPLE_VALUE_LIMIT) {
                        // Small enough to have seen every distinct value
                        // already - just use what we fetched.
                        values = preview;
                    } else {
                        // Too many to dump wholesale - search instead.
                        values = fetchFuzzyMatches(connection, tableName, columnName, question);
                    }

                    if (!values.isEmpty()) {
                        samples.put(columnName, values);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read sample values for table: " + tableName, e);
        }

        return samples;
    }

    private boolean isTextType(String columnType) {
        String upper = columnType.toUpperCase();
        return upper.contains("CHAR") || upper.contains("TEXT");
    }

    private List<String> fetchDistinctValues(Connection connection, String tableName, String columnName, int limit) throws SQLException {
        // tableName/columnName come from JDBC metadata (the database's own
        // catalog), never from user input, so string-building this SQL is
        // safe here in a way it would NOT be for anything derived from a
        // question or request body.
        String sql = "SELECT DISTINCT " + columnName + " FROM " + tableName + " LIMIT " + limit;
        List<String> values = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String value = resultSet.getString(1);
                if (value != null) {
                    values.add(value);
                }
            }
        }

        return values;
    }

    // Uses Postgres' pg_trgm extension (character-trigram overlap) to find
    // the real stored values that are textually closest to the question -
    // e.g. a slightly misspelled city name. The question itself is a bound
    // parameter here (unlike tableName/columnName above), because it IS
    // user input - never string-concatenated into SQL.
    private List<String> fetchFuzzyMatches(Connection connection, String tableName, String columnName, String question) throws SQLException {
        String sql = "SELECT " + columnName
                + " FROM (SELECT DISTINCT " + columnName + " FROM " + tableName + ") AS distinct_values"
                + " WHERE similarity(" + columnName + ", ?) > ?"
                + " ORDER BY similarity(" + columnName + ", ?) DESC"
                + " LIMIT ?";

        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, question);
            statement.setDouble(2, SIMILARITY_THRESHOLD);
            statement.setString(3, question);
            statement.setInt(4, FUZZY_MATCH_LIMIT);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String value = resultSet.getString(1);
                    if (value != null) {
                        values.add(value);
                    }
                }
            }
        }

        return values;
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
