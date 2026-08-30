package AskMyDb.SpringAI.AskMyDb.service;

import AskMyDb.SpringAI.AskMyDb.dto.LoginResponse;
import AskMyDb.SpringAI.AskMyDb.model.User;
import AskMyDb.SpringAI.AskMyDb.repository.UserRepository;
import AskMyDb.SpringAI.AskMyDb.exception.InvalidCredentialsException;
import AskMyDb.SpringAI.AskMyDb.exception.UsernameTakenException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    // Hashes the raw password with BCrypt before it ever touches the
    // database - the raw value is only held in memory for the duration of
    // this one call, never persisted or logged anywhere.
    public void register(String username, String rawPassword) {
        if (userRepository.existsByUsername(username)) {
            throw new UsernameTakenException("Username '" + username + "' is already taken.");
        }

        String hashedPassword = passwordEncoder.encode(rawPassword);
        userRepository.save(new User(username, hashedPassword));
    }

    // Verify credentials, then mint BOTH tokens: a short-lived access
    // token for calling the API, and a refresh token (stored in the DB)
    // for getting a new access token later without re-entering a password.
    // Same exception/message whether the username doesn't exist or the
    // password is wrong - see InvalidCredentialsException for why.
    public LoginResponse login(String username, String rawPassword) {
        Optional<User> user = userRepository.findByUsername(username);

        if (user.isEmpty() || !passwordEncoder.matches(rawPassword, user.get().getPassword())) {
            throw new InvalidCredentialsException("Invalid username or password.");
        }

        String accessToken = jwtService.generateToken(username);
        String refreshToken = refreshTokenService.issueRefreshToken(username);
        return new LoginResponse(accessToken, refreshToken);
    }

    // Trades a still-valid refresh token for a new pair of tokens. The old
    // refresh token is revoked as part of this (rotation) - it cannot be
    // used a second time, so a stolen-and-replayed old token is rejected
    // by RefreshTokenService.rotateAndGetUsername() as "already revoked".
    public LoginResponse refresh(String rawRefreshToken) {
        String username = refreshTokenService.rotateAndGetUsername(rawRefreshToken);
        String newAccessToken = jwtService.generateToken(username);
        String newRefreshToken = refreshTokenService.issueRefreshToken(username);
        return new LoginResponse(newAccessToken, newRefreshToken);
    }

    // Real logout: revokes the refresh token so it can never be exchanged
    // for a new access token again. The current access token (if any)
    // still works until it naturally expires within the hour - that's the
    // accepted tradeoff of a stateless access token (see PROGRESS.md).
    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }
}
