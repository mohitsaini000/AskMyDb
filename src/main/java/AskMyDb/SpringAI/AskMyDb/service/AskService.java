package AskMyDb.SpringAI.AskMyDb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

// Orchestrates the full pipeline: question -> generated SQL -> validated SQL -> real results.
// This is the "grounding" flow we discussed: the LLM only ever produces the SQL,
// the actual answer always comes from executing it against real data.
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    // How many times we let the LLM try to fix its own SQL after Postgres
    // rejects it (e.g. "column t2.name does not exist"). 1 retry means at
    // most 2 real attempts total - enough to fix the common "picked the
    // wrong table alias" mistake, without risking a slow, expensive loop if
    // the model keeps getting it wrong for a genuinely unanswerable question.
    private static final int MAX_CORRECTION_ATTEMPTS = 1;

    private final NlToSqlService nlToSqlService;
    private final SqlGuardrail sqlGuardrail;
    private final QueryExecutionService queryExecutionService;

    public AskService(NlToSqlService nlToSqlService, SqlGuardrail sqlGuardrail, QueryExecutionService queryExecutionService) {
        this.nlToSqlService = nlToSqlService;
        this.sqlGuardrail = sqlGuardrail;
        this.queryExecutionService = queryExecutionService;
    }

    public AskResult ask(String question) {
        long start = System.currentTimeMillis();

        String currentSql = sqlGuardrail.validateAndPrepare(nlToSqlService.generateSql(question));

        List<Map<String, Object>> rows = null;
        DataAccessException lastFailure = null;

        // attempt 0 = the original query. Each loop after a failure asks the
        // LLM to correct itself using the real Postgres error, re-validates
        // the corrected SQL through the same guardrail as any other
        // LLM-generated SQL (self-correction does not get to skip safety
        // checks just because it's a "fix"), and tries again.
        for (int attempt = 0; attempt <= MAX_CORRECTION_ATTEMPTS; attempt++) {
            try {
                rows = queryExecutionService.execute(currentSql);
                lastFailure = null;
                break;
            } catch (DataAccessException e) {
                lastFailure = e;
                if (attempt == MAX_CORRECTION_ATTEMPTS) {
                    break; // out of retries - let the caller see the real error
                }

                String errorMessage = e.getMostSpecificCause().getMessage();
                log.warn("SQL execution failed (attempt {}) - asking the LLM to self-correct. Error: {}",
                        attempt + 1, errorMessage);

                String correctedSql = nlToSqlService.regenerateSql(question, currentSql, errorMessage);
                currentSql = sqlGuardrail.validateAndPrepare(correctedSql);
            }
        }

        long totalElapsed = System.currentTimeMillis() - start;
        log.info("TIMING - total request time (including any self-correction retries): {} ms", totalElapsed);

        if (lastFailure != null) {
            // Still failing after every retry - rethrow so AskController's
            // existing DataAccessException handler turns it into the same
            // clean 422 ProblemDetail response it always has.
            throw lastFailure;
        }

        return new AskResult(question, currentSql, rows);
    }
}
