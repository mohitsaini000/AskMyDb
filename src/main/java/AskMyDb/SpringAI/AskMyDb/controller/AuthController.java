package AskMyDb.SpringAI.AskMyDb.controller;

import AskMyDb.SpringAI.AskMyDb.dto.LoginRequest;
import AskMyDb.SpringAI.AskMyDb.dto.LoginResponse;
import AskMyDb.SpringAI.AskMyDb.dto.RegisterRequest;
import AskMyDb.SpringAI.AskMyDb.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Stage A: registration. Stage B: login, which verifies credentials and
// hands back a JWT. Every endpoint here is still permitAll() in
// SecurityConfig for now - Stage C adds the filter that actually reads the
// token on OTHER (protected) endpoints and rejects requests without one.
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

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        String token = authService.login(request.username(), request.password());
        return ResponseEntity.ok(new LoginResponse(token));
    }
}
