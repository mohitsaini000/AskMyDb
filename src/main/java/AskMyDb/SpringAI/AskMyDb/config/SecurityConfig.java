package AskMyDb.SpringAI.AskMyDb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

// TEMPORARY: still permit-all while JWT auth is being built in stages
// (Stage A: registration - this step. Stage B: login + token issuing.
// Stage C: a JwtAuthFilter that actually verifies tokens and locks down
// every endpoint except /api/auth/**). Only once Stage C lands does this
// class start rejecting unauthenticated requests - until then, wiring in
// the security rules early would just break every endpoint before there's
// any way to get a valid token to call them with.
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())
            .formLogin(formLogin -> formLogin.disable())
            .httpBasic(httpBasic -> httpBasic.disable());
        return http.build();
    }

    // BCrypt: a one-way hashing algorithm purpose-built for passwords - it's
    // deliberately slow (to resist brute-force attempts) and automatically
    // salts each hash (so two users with the same password get different
    // hashes). AuthService uses this to hash a password before saving it,
    // and will use it again in Stage B to verify a login attempt without
    // ever storing or comparing the raw password directly.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
