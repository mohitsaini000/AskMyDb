package AskMyDb.SpringAI.AskMyDb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Turns a plain-English question into a SQL query using the LLM,
// grounded in a *retrieved* slice of the schema (via the vector store) so it
// doesn't hallucinate table/column names that don't exist - and so the
// prompt stays small even as the real schema grows to hundreds of tables.
//
// IMPORTANT: this service ONLY generates SQL text. It does NOT execute it.
// Execution + safety checks come in a later step (guardrails first, always).
@Service
public class NlToSqlService {

    private static final Logger log = LoggerFactory.getLogger(NlToSqlService.class);

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final SchemaService schemaService;
    private final int topK;
    private final int schemaLinkHops;

    public NlToSqlService(ChatClient chatClient, VectorStore vectorStore, SchemaService schemaService,
                           @Value("${askmydb.rag.top-k:3}") int topK,
                           @Value("${askmydb.rag.schema-link-hops:2}") int schemaLinkHops) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.schemaService = schemaService;
        this.topK = topK;
        this.schemaLinkHops = schemaLinkHops;
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
            - Only join two tables directly if one of them lists the other in its
              "Foreign keys" section below. If what the question needs spans two tables
              that are NOT directly connected by a listed foreign key, you must join
              through every intermediate table that connects them, one hop at a time -
              never invent a direct join between two tables just because they both
              happen to have a similarly-named id column. See the fourth example below.
            - Some columns below list "Example values" - the actual distinct values
              that column really contains in the database. If the question mentions a
              value that is not an exact match to any listed example, but you recognize
              it as a common alternate name or spelling for one of them (e.g. "Bangalore"
              and "Bengaluru" are the same city), use the exact value shown in the schema
              in your WHERE clause, not the word the user typed.

            Example of correctly deriving a value that has no direct column:
            Question: What is the total value of all shipped orders?
            SQL: SELECT SUM(oi.quantity * oi.unit_price) FROM order_items oi JOIN orders o ON oi.order_id = o.id WHERE o.status = 'SHIPPED';

            Example of correctly handling ties instead of LIMIT 1:
            Question: Which city has the most customers? If more than one city ties for the top count, list all of them.
            SQL: SELECT city FROM customers GROUP BY city HAVING COUNT(*) = (SELECT MAX(city_count) FROM (SELECT COUNT(*) AS city_count FROM customers GROUP BY city) AS counts);

            Example of correctly refusing an unrelated question instead of inventing a table:
            Question: What's today's weather in Bengaluru?
            SQL: CANNOT_ANSWER

            Example of correctly joining through an intermediate table instead of
            skipping it (customers and products are not directly connected by any
            foreign key - orders and order_items are the bridge tables in between):
            Question: List customers in a given city along with the products they've bought.
            SQL: SELECT c.name, p.name, p.category FROM customers c JOIN orders o ON o.customer_id = c.id JOIN order_items oi ON oi.order_id = o.id JOIN products p ON p.id = oi.product_id WHERE c.city = 'Chennai';

            The tables below were retrieved as the ones most relevant to this specific
            question - they may not be every table in the database. If answering
            properly would require a table that genuinely isn't listed here, that is
            still the CANNOT_ANSWER case above; do not guess at a table that isn't shown.

            Database schema:
            %s
            """;

    public String generateSql(String question) {
        long start = System.currentTimeMillis();
        String schemaDescription = retrieveRelevantSchema(question);
        long afterRetrieval = System.currentTimeMillis();

        String systemPrompt = String.format(SYSTEM_TEMPLATE, schemaDescription);

        String rawResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .content();
        long afterLlmCall = System.currentTimeMillis();

        // TEMPORARY diagnostic logging - splitting retrieval (vector search +
        // JDBC metadata reads) from the actual LLM generation call, so we can
        // see which one is actually slow instead of guessing.
        log.info("TIMING - schema retrieval: {} ms | LLM call: {} ms",
                afterRetrieval - start, afterLlmCall - afterRetrieval);

        return cleanSql(rawResponse);
    }

    // RAG retrieval step: similaritySearch embeds the question under the hood
    // and compares it against the table embeddings SchemaIndexer stored
    // earlier, returning only the topK closest matches - not the whole
    // schema. This is the "retrieval" half of the pipeline.
    //
    // On its own, this has a real gap: it only finds tables whose *wording*
    // resembles the question. It has no idea a question that mentions
    // "customers" and "revenue" also needs the "orders" table to JOIN them
    // together, if "orders" itself didn't score high enough to make the
    // topK cut. So this is followed by a "schema linking" expansion step:
    // for every table retrieval found, pull in every table it's directly
    // foreign-key-connected to as well, even if that neighbor scored low
    // on its own. That's what makes JOINs reliably possible instead of
    // depending on semantic similarity to also predict join structure.
    private String retrieveRelevantSchema(String question) {
        List<Document> seedMatches = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(topK).build());

        // TEMPORARY diagnostic logging - which tables did the similarity
        // search itself pick as seeds, before FK expansion touches anything.
        List<String> seedTableNames = seedMatches.stream()
                .map(m -> (String) m.getMetadata().get("table"))
                .toList();
        log.info("DEBUG - seed tables from vector similarity search: {}", seedTableNames);

        Set<String> seedTables = new LinkedHashSet<>();
        for (Document match : seedMatches) {
            String tableName = (String) match.getMetadata().get("table");
            if (tableName != null) {
                seedTables.add(tableName);
            }
        }

        Set<String> relevantTables = expandViaForeignKeys(seedTables, schemaLinkHops);

        // TEMPORARY diagnostic logging - the final table set after
        // multi-hop FK expansion, i.e. exactly what schema text gets
        // shown to the LLM.
        log.info("DEBUG - final tables after {}-hop FK expansion (sent to LLM): {}", schemaLinkHops, relevantTables);

        Map<String, String> allDescriptions = schemaService.getTableDescriptions();

        StringBuilder schemaText = new StringBuilder();
        for (String table : relevantTables) {
            String description = allDescriptions.get(table);
            if (description == null) {
                continue;
            }
            schemaText.append(description);

            // Value linking: only fetched for the small number of tables
            // actually being shown to the LLM this question, never for
            // every table in the database - this is a live query against
            // real data, not something we want to run unnecessarily.
            Map<String, List<String>> sampleValues = schemaService.getSampleValues(table, question);
            for (Map.Entry<String, List<String>> entry : sampleValues.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    continue;
                }
                schemaText.append("  Example values for ").append(entry.getKey())
                        .append(": ").append(String.join(", ", entry.getValue())).append("\n");
            }

            schemaText.append("\n");
        }

        return schemaText.toString();
    }

    // Multi-hop schema linking: getRelatedTables() only finds a table's
    // *direct* FK neighbors. That's not always enough - a "bridge" table
    // (e.g. order_items, sitting between orders and products) might not
    // resemble the question's wording at all, so it never becomes a seed,
    // AND it might be two FK-hops away from every seed table, so a single
    // round of expansion misses it too. This does a breadth-first search
    // outward from the seed tables instead of stopping after one hop:
    // hop 1 = seeds' direct neighbors, hop 2 = their neighbors, and so on
    // up to maxHops.
    //
    // This is a real precision/recall trade-off, not a free win: each extra
    // hop can pull in more tables than intended on a densely-connected
    // schema, making the prompt bigger (slower, costlier LLM calls) and
    // potentially giving the model more irrelevant tables to get confused
    // by. maxHops is deliberately small (askmydb.rag.schema-link-hops,
    // default 2) rather than "expand until nothing new is found".
    private Set<String> expandViaForeignKeys(Set<String> seedTables, int maxHops) {
        Set<String> visited = new LinkedHashSet<>(seedTables);
        Set<String> frontier = seedTables;

        for (int hop = 0; hop < maxHops && !frontier.isEmpty(); hop++) {
            Set<String> nextFrontier = new LinkedHashSet<>();
            for (String table : frontier) {
                for (String related : schemaService.getRelatedTables(table)) {
                    // visited.add() returns false if this table was already
                    // found on an earlier hop - only genuinely NEW tables
                    // become part of the next hop's starting point, so we
                    // never re-expand the same table twice.
                    if (visited.add(related)) {
                        nextFrontier.add(related);
                    }
                }
            }
            frontier = nextFrontier;
        }

        return visited;
    }

    // Self-correction step: called only when a previously-generated query was
    // already validated as safe (a real SELECT, no forbidden keywords) but
    // then failed when Postgres actually ran it - e.g. "column t2.name does
    // not exist". That's a mistake the LLM made about which table a column
    // belongs to, not a schema retrieval problem, so we hand it the exact
    // Postgres error (which is often very specific, even naming the column
    // it thinks you meant) and ask it to fix its own query.
    public String regenerateSql(String question, String previousSql, String errorMessage) {
        long start = System.currentTimeMillis();
        String schemaDescription = retrieveRelevantSchema(question);
        String systemPrompt = String.format(SYSTEM_TEMPLATE, schemaDescription);

        String correctionPrompt = String.format("""
                The original question was: %s

                You previously generated this SQL query:
                %s

                Running it against the real database failed with this error:
                %s

                Write a corrected SQL query that fixes this specific error, while
                still following all the rules above and still answering the
                original question. Output ONLY the corrected raw SQL query.
                """, question, previousSql, errorMessage);

        String rawResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(correctionPrompt)
                .call()
                .content();
        long elapsed = System.currentTimeMillis() - start;

        log.info("TIMING - SQL self-correction retry: {} ms", elapsed);

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
