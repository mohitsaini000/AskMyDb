package AskMyDb.SpringAI.AskMyDb.controller;

import AskMyDb.SpringAI.AskMyDb.dto.AskRequest;
import AskMyDb.SpringAI.AskMyDb.dto.AskResponse;
import AskMyDb.SpringAI.AskMyDb.service.AskResult;
import AskMyDb.SpringAI.AskMyDb.service.AskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// The real, production-shaped endpoint: POST a JSON question, get back
// the SQL that was run plus real data - or a clean, structured error
// (all error handling now lives centrally in GlobalExceptionHandler).
//
// Try in Postman / curl:
//   POST http://localhost:8080/api/ask
//   Content-Type: application/json
//   { "question": "How many customers do we have?" }
@RestController
@RequestMapping("/api")
public class AskController {

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        AskResult result = askService.ask(request.question());
        return new AskResponse(result.question(), result.sql(), result.rows());
    }
}
