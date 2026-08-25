package AskMyDb.SpringAI.AskMyDb.service;

import java.util.List;
import java.util.Map;

// What we hand back to the caller: the original question, the exact SQL
// that was run (so the user can verify/trust the answer - remember our
// "transparency" feature idea), and the actual rows from the database.
public record AskResult(String question, String sql, List<Map<String, Object>> rows) {}
