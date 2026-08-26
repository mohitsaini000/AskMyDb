package AskMyDb.SpringAI.AskMyDb.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

// Turns a plain-English question into a SQL query using the LLM,
// grounded in the real schema (via SchemaService) so it doesn't hallucinate
// table/column names that don't exist.
//
// IMPORTANT: this service ONLY generates SQL text. It does NOT execute it.
// Execution + safety checks come in a later step (guardrails first, always).
@Service
public class NlToSqlService {

    private final ChatClient chatClient;
    private final SchemaService schemaService;

    public NlToSqlService(ChatClient chatClient, SchemaService schemaService) {
        this.chatClient = chatClient;
        this.schemaService = schemaService;
    }

    private static final String SYSTEM_TEMPLATE = """
            You are an expert PostgreSQL developer.
            Given the database schema below and a user's question in plain English,
            write exactly ONE PostgreSQL SELECT query that answers the question.

            Strict rules:
            - Output ONLY the raw SQL query. No explanation, no comments, no markdown code fences.
            - Only ever write a SELECT statement. Never write INSERT, UPDATE, DELETE, DROP,
              ALTER, TRUNCATE, CREATE, GRANT, or any statement that changes data or schema.
            - Only use tables and columns that appear in the schema below. Never invent names,
              even if a word in the question sounds like a plausible table or column (e.g. do
              not invent a `cities` or `weather` table just because the question mentions a
              city or the weather). If no table or column in the schema relates to what is
              being asked, that is exactly the CANNOT_ANSWER case below - do not guess a name.
            - Do NOT assume a table has a common-sounding column (like total_amount, revenue,
              total) unless it is explicitly listed below. If a value must be derived (e.g. an
              order's total), compute it from the raw columns that actually exist, using JOINs
              and aggregate functions (SUM, COUNT, etc.) as needed.
            - If the question cannot be answered using this schema, output exactly: CANNOT_ANSWER
            - If the question asks for the "top" / "most" / "highest" / "least" item(s) and
              also says what to do about ties (e.g. "if more than one has the same count,
              list all of them"), never use LIMIT 1 for that. LIMIT 1 silently drops ties.
              Instead select every row whose aggregate equals the maximum (or minimum),
              using HAVING with a subquery that computes MAX()/MIN() over the grouped
              aggregate, as shown in the second example below.

            Example of correctly deriving a value that has no direct column:
            Question: What is the total value of all shipped orders?
            SQL: SELECT SUM(oi.quantity * oi.unit_price) FROM order_items oi JOIN orders o ON oi.order_id = o.id WHERE o.status = 'SHIPPED';

            Example of correctly handling ties instead of LIMIT 1:
            Question: Which city has the most customers? If more than one city ties for the top count, list all of them.
            SQL: SELECT city FROM customers GROUP BY city HAVING COUNT(*) = (SELECT MAX(city_count) FROM (SELECT COUNT(*) AS city_count FROM customers GROUP BY city) AS counts);

            Example of correctly refusing an unrelated question instead of inventing a table:
            Question: What's today's weather in Bengaluru?
            SQL: CANNOT_ANSWER

            Database schema:
            %s
            """;

    public String generateSql(String question) {
        String schemaDescription = schemaService.getSchemaDescription();
        String systemPrompt = String.format(SYSTEM_TEMPLATE, schemaDescription);

        String rawResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .content();

        return cleanSql(rawResponse);
    }

    // Small but important: models sometimes ignore the "no markdown" instruction
    // and wrap SQL in ```sql ... ``` anyway. Strip that defensively rather than
    // trusting the model's formatting 100% of the time.
    private String cleanSql(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*", "");
            cleaned = cleaned.replaceAll("```$", "");
            cleaned = cleaned.trim();
        }
        return cleaned;
    }
}
