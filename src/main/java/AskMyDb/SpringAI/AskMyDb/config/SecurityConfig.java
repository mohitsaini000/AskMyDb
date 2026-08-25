package AskMyDb.SpringAI.AskMyDb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

// TEMPORARY: Spring Security auto-generates a login page + random password
// as soon as spring-boot-starter-security is on the classpath. We haven't
// built real authentication yet (planned: JWT-based auth, later step), so
// for now we explicitly permit every request and turn off the default
// login form / basic-auth prompts, purely so we can test endpoints freely
// during development. This file will be replaced once real auth is added.
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
}
