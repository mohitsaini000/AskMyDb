package AskMyDb.SpringAI.AskMyDb.security;

import AskMyDb.SpringAI.AskMyDb.service.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

// Runs once per incoming request, before it reaches any controller. Its
// only job is: if a valid JWT is present, tell Spring Security who this
// request is from. It does NOT reject requests itself - which endpoints
// require auth at all is SecurityConfig's decision. A missing or invalid
// token here just leaves the request "anonymous", and SecurityConfig's
// rules decide whether that's allowed for this particular URL.
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring("Bearer ".length());

            try {
                // Signature + expiry are both checked inside this call
                // (parseSignedClaims throws if either is wrong). No DB hit
                // here - the whole point of the token is that it already
                // proves identity on its own.
                String username = jwtService.extractUsername(token);

                var authentication = new UsernamePasswordAuthenticationToken(
                        username, null, Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException e) {
                // Bad signature, expired, malformed - whatever the reason,
                // just leave the request unauthenticated and let
                // SecurityConfig's rules decide if that's allowed here.
                log.debug("Rejected invalid JWT: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }
}
