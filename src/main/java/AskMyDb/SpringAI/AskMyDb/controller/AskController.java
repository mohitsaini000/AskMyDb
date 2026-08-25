package AskMyDb.SpringAI.AskMyDb.controller;

import AskMyDb.SpringAI.AskMyDb.dto.AskRequest;
import AskMyDb.SpringAI.AskMyDb.dto.AskResponse;
import AskMyDb.SpringAI.AskMyDb.service.AskResult;
import AskMyDb.SpringAI.AskMyDb.service.AskService;
import AskMyDb.SpringAI.AskMyDb.service.UnsafeSqlException;
import jakarta.validation.Valid;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Collectors;

// The real, production-shaped endpoint: POST a JSON question, get back
// the SQL that was run plus real data - or a clean, structured error.
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

    // Bean Validation (@NotBlank/@Size on AskRequest) throws this when it fails.
    // We turn it into a clean, structured 400 instead of Spring's default
    // verbose validation error dump.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
        problem.setTitle("Validation failed");
        return problem;
    }

    // Our own guardrail rejected the generated SQL before it ever touched the database.
    @ExceptionHandler(UnsafeSqlException.class)
    public ProblemDetail handleUnsafeSql(UnsafeSqlException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Unsafe or invalid SQL");
        return problem;
    }

    // Postgres itself rejected the SQL while running it (e.g. a column that
    // doesn't exist) - caught here so it becomes a clean error, not a crash.
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail handleDataAccess(DataAccessException e) {
        String reason = e.getMostSpecificCause().getMessage();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, reason);
        problem.setTitle("SQL execution failed");
        return problem;
    }
}
