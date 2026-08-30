package AskMyDb.SpringAI.AskMyDb.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

// Unlike the access token (a self-contained JWT nobody stores anywhere),
// a refresh token is deliberately a database row - that's the whole point
// of having one. Storing it is what makes it revocable: logging out, or
// noticing a token got reused after rotation, means flipping `revoked` to
// true here, which an access token's stateless design can never support.
//
// We never store the raw token value - only its SHA-256 hash
// (RefreshTokenService). If this table ever leaked, the hashes alone
// aren't usable to impersonate anyone, the same reasoning as storing a
// BCrypt hash instead of a raw password.
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String tokenHash;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private Instant expiryDate;

    @Column(nullable = false)
    private boolean revoked = false;

    protected RefreshToken() {
    }

    public RefreshToken(String tokenHash, String username, Instant expiryDate) {
        this.tokenHash = tokenHash;
        this.username = username;
        this.expiryDate = expiryDate;
    }

    public Long getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getUsername() {
        return username;
    }

    public Instant getExpiryDate() {
        return expiryDate;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void revoke() {
        this.revoked = true;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiryDate);
    }
}
