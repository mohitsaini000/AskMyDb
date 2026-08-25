package AskMyDb.SpringAI.AskMyDb.service;

import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

// Reads the real database structure at request time using plain JDBC metadata APIs
// (not JPA/Hibernate) so it works even though we don't have @Entity classes -
// we don't know the shape of the data ahead of time, the LLM needs to "see" it.
@Service
public class SchemaService {

    private final DataSource dataSource;

    public SchemaService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // Builds a plain-text description of every table + its columns in the
    // "public" schema, e.g.:
    //
    // Table: customers
    //   - id (integer)
    //   - name (varchar)
    //   ...
    //
    // This text is what gets dropped into the LLM's prompt so it knows what
    // tables/columns actually exist and won't invent ones that don't.
    public String getSchemaDescription() {
        StringBuilder sb = new StringBuilder();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();

            try (ResultSet tables = metaData.getTables(null, "public", "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    sb.append("Table: ").append(tableName).append("\n");

                    try (ResultSet columns = metaData.getColumns(null, "public", tableName, "%")) {
                        while (columns.next()) {
                            String columnName = columns.getString("COLUMN_NAME");
                            String columnType = columns.getString("TYPE_NAME");
                            sb.append("  - ").append(columnName).append(" (").append(columnType).append(")\n");
                        }
                    }
                    sb.append("\n");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read database schema", e);
        }

        return sb.toString();
    }
}
