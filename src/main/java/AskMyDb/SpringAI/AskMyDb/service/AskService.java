package AskMyDb.SpringAI.AskMyDb.service;

import org.springframework.stereotype.Service;

// Orchestrates the full pipeline: question -> generated SQL -> validated SQL -> real results.
// This is the "grounding" flow we discussed: the LLM only ever produces the SQL,
// the actual answer always comes from executing it against real data.
@Service
public class AskService {

    private final NlToSqlService nlToSqlService;
    private final SqlGuardrail sqlGuardrail;
    private final QueryExecutionService queryExecutionService;

    public AskService(NlToSqlService nlToSqlService, SqlGuardrail sqlGuardrail, QueryExecutionService queryExecutionService) {
        this.nlToSqlService = nlToSqlService;
        this.sqlGuardrail = sqlGuardrail;
        this.queryExecutionService = queryExecutionService;
    }

    public AskResult ask(String question) {
        String rawSql = nlToSqlService.generateSql(question);
        String safeSql = sqlGuardrail.validateAndPrepare(rawSql);
        var rows = queryExecutionService.execute(safeSql);
        return new AskResult(question, safeSql, rows);
    }
}
