package AskMyDb.SpringAI.AskMyDb.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;

// Stage B of JWT auth: this class only knows how to MINT a token once a
// user has already proven who they are (AuthService.login already checked
// the password). It does not verify incoming tokens yet - that's the
// JwtAuthFilter we add in Stage C, which reuses the same secret key to
// recompute and check a signature on every protected request.
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(
            @Value("${askmydb.jwt.secret}") String base64Secret,
            @Value("${askmydb.jwt.expiration-ms}") long expirationMs) {
        // Turns the base64 text in application.yaml into the actual
        // SecretKey object the signing algorithm needs.
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
        this.expirationMs = expirationMs;
    }

    // Builds header.payload.signature and returns it as one string.
    // "subject" is the username - the one piece of identity the rest of
    // the app needs to read back out of the token later (in Stage C).
    public String generateToken(String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    // Stage C: verifies the signature and expiry, then reads the username
    // back out of the payload. parseSignedClaims() throws a JwtException
    // (or a subtype - ExpiredJwtException, SignatureException,
    // MalformedJwtException) if anything is wrong; JwtAuthFilter decides
    // what to do with that, this method doesn't swallow it.
    public String extractUsername(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }
}
