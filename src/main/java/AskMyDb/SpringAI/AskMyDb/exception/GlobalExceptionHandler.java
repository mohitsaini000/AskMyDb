package AskMyDb.SpringAI.AskMyDb.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

// Single, app-wide place that turns exceptions into clean HTTP responses.
// Before this, AskController and AuthController each carried their own
// @ExceptionHandler methods - duplicated boilerplate that would only grow
// as more controllers get added. @RestControllerAdvice applies to every
// @RestController in the application automatically, so there's exactly one
// place that decides "this kind of failure becomes this kind of response" -
// the standard production pattern instead of scattering handlers per class.
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Bean Validation (@NotBlank/@Size on request DTOs like AskRequest and
    // RegisterRequest) throws this when it fails. Turned into a clean,
    // structured 400 instead of Spring's default verbose validation dump.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
        problem.setTitle("Validation failed");
        return problem;
    }

    // Our own guardrail rejected the generated SQL before it ever touched
    // the database, or the model explicitly said it couldn't answer.
    @ExceptionHandler(UnsafeSqlException.class)
    public ProblemDetail handleUnsafeSql(UnsafeSqlException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Unsafe or invalid SQL");
        return problem;
    }

    // Registration attempted with a username that's already taken.
    @ExceptionHandler(UsernameTakenException.class)
    public ProblemDetail handleUsernameTaken(UsernameTakenException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Registration failed");
        return problem;
    }

    // Login attempted with a username that doesn't exist, or a password
    // that doesn't match. Same response either way (see
    // InvalidCredentialsException) so the client can't tell which one it
    // was - that's the whole point of a 401 here instead of a 404/400.
    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setTitle("Authentication failed");
        return problem;
    }

    // Postgres itself rejected the SQL while running it (e.g. a column that
    // doesn't exist), and self-correction (AskService's retry loop) still
    // couldn't fix it after its one allowed attempt.
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail handleDataAccess(DataAccessException e) {
        String reason = e.getMostSpecificCause().getMessage();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, reason);
        problem.setTitle("SQL execution failed");
        return problem;
    }

    // Last-resort safety net: anything not already handled above (e.g. the
    // generic RuntimeExceptions SchemaService throws on a genuine JDBC/
    // connectivity failure). Logs the full exception with its stack trace
    // server-side - so it's still fully debuggable from the logs - but
    // never leaks internals (stack traces, exception class names, raw
    // driver messages) to the client. A production API should never return
    // more detail to the caller than it's safe for them to see.
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again.");
        problem.setTitle("Internal server error");
        return problem;
    }
}
