package AskMyDb.SpringAI.AskMyDb.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

// Executes ALREADY-VALIDATED SQL (never call this directly with raw LLM
// output - it must go through SqlGuardrail first) using plain JdbcTemplate,
// not JPA, since we don't know the result shape ahead of time.
@Service
public class QueryExecutionService {

    private final JdbcTemplate jdbcTemplate;

    public QueryExecutionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> execute(String sql) {
        return jdbcTemplate.queryForList(sql);
    }
}
