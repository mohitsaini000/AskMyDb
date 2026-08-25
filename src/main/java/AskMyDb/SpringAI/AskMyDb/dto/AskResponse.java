package AskMyDb.SpringAI.AskMyDb.dto;

import java.util.List;
import java.util.Map;

// What we send back on success: the original question, the exact SQL that
// was run (transparency - the user can verify the answer), and the rows.
public record AskResponse(String question, String sql, List<Map<String, Object>> rows) {}
