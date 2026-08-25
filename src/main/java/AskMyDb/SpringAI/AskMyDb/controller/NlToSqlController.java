package AskMyDb.SpringAI.AskMyDb.controller;

import AskMyDb.SpringAI.AskMyDb.service.NlToSqlService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Temporary test endpoint for Task 4: shows the SQL the LLM generates for a
// question, WITHOUT executing it. This is deliberate - we verify the model's
// output looks correct before we ever let it run against a real database.
// Try: GET http://localhost:8080/api/generate-sql?question=How many customers do we have?
@RestController
public class NlToSqlController {

    private final NlToSqlService nlToSqlService;

    public NlToSqlController(NlToSqlService nlToSqlService) {
        this.nlToSqlService = nlToSqlService;
    }

    @GetMapping("/api/generate-sql")
    public String generateSql(@RequestParam String question) {
        return nlToSqlService.generateSql(question);
    }
}
