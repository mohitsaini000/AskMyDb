package AskMyDb.SpringAI.AskMyDb.config;

import AskMyDb.SpringAI.AskMyDb.security.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// Stage C: registration and login stay public (that's the only way to get
// a token in the first place) - everything else now requires a valid JWT.
// JwtAuthFilter runs before Spring's own UsernamePasswordAuthenticationFilter
// and, if the request carries a valid token, marks it authenticated; this
// class only decides which URLs are allowed to proceed without one.
@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/auth/**").permitAll()
                    // The static test page itself (index.html) must be
                    // reachable WITHOUT a token - it's the page that
                    // contains the sign-in form in the first place. Only
                    // the actual API calls it makes (/api/ask etc.) stay
                    // behind auth.
                    .requestMatchers("/", "/index.html", "/favicon.ico").permitAll()
                    .anyRequest().authenticated())
            .csrf(csrf -> csrf.disable())
            .formLogin(formLogin -> formLogin.disable())
            .httpBasic(httpBasic -> httpBasic.disable())
            // No server-side session - each request proves itself with its
            // own token instead of relying on a session cookie, matching
            // the stateless design JWT is meant for.
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    // BCrypt: a one-way hashing algorithm purpose-built for passwords - it's
    // deliberately slow (to resist brute-force attempts) and automatically
    // salts each hash (so two users with the same password get different
    // hashes). AuthService uses this to hash a password before saving it,
    // and again during login to verify an attempt without ever storing or
    // comparing the raw password directly.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
