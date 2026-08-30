package AskMyDb.SpringAI.AskMyDb.controller;

import AskMyDb.SpringAI.AskMyDb.dto.LoginRequest;
import AskMyDb.SpringAI.AskMyDb.dto.LoginResponse;
import AskMyDb.SpringAI.AskMyDb.dto.RefreshRequest;
import AskMyDb.SpringAI.AskMyDb.dto.RegisterRequest;
import AskMyDb.SpringAI.AskMyDb.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Every endpoint here is permitAll() in SecurityConfig - that's the only
// way a client can ever get a first token, refresh an expired one, or
// revoke one, without already having a valid token to begin with.
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
        LoginResponse response = authService.login(request.username(), request.password());
        return ResponseEntity.ok(response);
    }

    // Exchanges a still-valid, not-yet-used refresh token for a new
    // access token + refresh token pair. Called automatically by a
    // client when its access token has expired - no password needed.
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        LoginResponse response = authService.refresh(request.refreshToken());
        return ResponseEntity.ok(response);
    }

    // Revokes the given refresh token so it can't be used again. This is
    // what makes "logout" mean something for JWT auth - see AuthService.
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
