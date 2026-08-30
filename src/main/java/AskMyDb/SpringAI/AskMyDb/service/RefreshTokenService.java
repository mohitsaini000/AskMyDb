package AskMyDb.SpringAI.AskMyDb.service;

import AskMyDb.SpringAI.AskMyDb.exception.InvalidRefreshTokenException;
import AskMyDb.SpringAI.AskMyDb.model.RefreshToken;
import AskMyDb.SpringAI.AskMyDb.repository.RefreshTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

// Refresh tokens are NOT JWTs - they're just long random strings, looked
// up in the database rather than verified by signature. That's the whole
// point: a JWT can't be revoked before it expires, but a database row can
// have its `revoked` flag flipped at any time (logout, or reuse detection
// below).
@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final long refreshExpirationMs;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            @Value("${askmydb.jwt.refresh-expiration-ms}") long refreshExpirationMs) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    // Creates a brand new refresh token for this user, stores only its
    // hash, and returns the RAW value - this is the one and only moment
    // the raw token exists outside this method. Once it's handed back to
    // the caller, we can never look it up by anything other than its hash.
    public String issueRefreshToken(String username) {
        String rawToken = generateRawToken();
        RefreshToken entity = new RefreshToken(
                hash(rawToken),
                username,
                Instant.now().plusMillis(refreshExpirationMs));
        refreshTokenRepository.save(entity);
        return rawToken;
    }

    // Validates a refresh token presented by the client, then immediately
    // revokes it (rotation: every refresh token is single-use - the caller
    // is expected to call issueRefreshToken() right after this to hand the
    // client a new one). Returns the username it belonged to.
    public String rotateAndGetUsername(String rawToken) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token is invalid."));

        if (stored.isRevoked()) {
            // This exact token was already used once before (or logged
            // out). Somebody presenting it again is exactly the "stolen
            // and replayed" scenario rotation exists to catch.
            throw new InvalidRefreshTokenException("Refresh token has already been used or revoked.");
        }
        if (stored.isExpired()) {
            throw new InvalidRefreshTokenException("Refresh token has expired.");
        }

        stored.revoke();
        refreshTokenRepository.save(stored);
        return stored.getUsername();
    }

    // Logout: revoke this one token so it can never be used to mint a new
    // access token again. Silently does nothing if the token is unknown -
    // the caller's goal ("this token should stop working") is already
    // true either way, and there's no reason to hand back information
    // about which refresh tokens do or don't exist.
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken))
                .ifPresent(token -> {
                    token.revoke();
                    refreshTokenRepository.save(token);
                });
    }

    private String generateRawToken() {
        byte[] randomBytes = new byte[64];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    // SHA-256 is fine here (unlike BCrypt for passwords) because a refresh
    // token isn't a human-guessable secret - it's 64 bytes of secure
    // randomness. BCrypt's deliberate slowness defends against brute-force
    // guessing of low-entropy passwords; that threat doesn't apply here,
    // and a fast hash is what lets a single indexed DB lookup find it.
    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
