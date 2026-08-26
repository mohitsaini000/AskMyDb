package AskMyDb.SpringAI.AskMyDb.controller;

import AskMyDb.SpringAI.AskMyDb.dto.RegisterRequest;
import AskMyDb.SpringAI.AskMyDb.service.AuthService;
import AskMyDb.SpringAI.AskMyDb.service.UsernameTakenException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Stage A of JWT auth: registration only. Login (which actually issues a
// JWT) and the filter that verifies it on protected requests come next -
// this endpoint is independently testable on its own first: register a
// user, then confirm a row landed in app_users with a BCrypt hash (never
// the raw password) in its password column.
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request.username(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @ExceptionHandler(UsernameTakenException.class)
    public ProblemDetail handleUsernameTaken(UsernameTakenException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Registration failed");
        return problem;
    }
}
