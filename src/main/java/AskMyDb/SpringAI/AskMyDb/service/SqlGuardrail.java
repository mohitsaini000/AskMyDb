package AskMyDb.SpringAI.AskMyDb.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

// This is the safety layer between "text an LLM produced" and "SQL that
// actually touches our real database". We NEVER execute generated SQL
// without passing it through here first. Treat LLM output the same way
// you'd treat untrusted user input.
@Component
public class SqlGuardrail {

    // Any of these appearing as a whole word anywhere in the query is rejected.
    // We check as WHOLE WORDS (using \b word boundaries) so we don't false-positive
    // on a legitimate column name like "updated_at" or "created_by" just because
    // it contains the substring "update"/"create".
    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE",
            "CREATE", "GRANT", "REVOKE", "EXEC", "EXECUTE", "CALL", "MERGE"
    );

    // Safety net in case the model forgets to limit a wide-open query
    // (e.g. "SELECT * FROM orders" on a table with millions of rows).
    private static final int DEFAULT_ROW_LIMIT = 100;

    // Returns a "safe to run" version of the SQL, or throws UnsafeSqlException
    // explaining exactly why it was rejected.
    public String validateAndPrepare(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new UnsafeSqlException("The AI did not return a query.");
        }

        String trimmed = sql.trim();

        if ("CANNOT_ANSWER".equalsIgnoreCase(trimmed)) {
            throw new UnsafeSqlException("This question can't be answered with the current database schema.");
        }

        // Block "SELECT ...; DROP TABLE ...;" style multi-statement injection.
        // A single trailing semicolon is fine, but anything after it is not.
        String noTrailingSemicolon = trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        if (noTrailingSemicolon.contains(";")) {
            throw new UnsafeSqlException("Multiple SQL statements are not allowed.");
        }

        String upper = noTrailingSemicolon.toUpperCase();

        if (!upper.startsWith("SELECT")) {
            throw new UnsafeSqlException("Only SELECT queries are allowed.");
        }

        for (String keyword : FORBIDDEN_KEYWORDS) {
            if (Pattern.compile("\\b" + keyword + "\\b").matcher(upper).find()) {
                throw new UnsafeSqlException("Query contains a forbidden keyword: " + keyword);
            }
        }

        if (!upper.contains("LIMIT")) {
            noTrailingSemicolon = noTrailingSemicolon + " LIMIT " + DEFAULT_ROW_LIMIT;
        }

        return noTrailingSemicolon;
    }
}
