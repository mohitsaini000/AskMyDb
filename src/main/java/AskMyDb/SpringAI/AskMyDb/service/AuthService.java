package AskMyDb.SpringAI.AskMyDb.service;

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

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
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

    // Stage B: verify credentials, then mint a JWT if they're correct.
    // Deliberately throws the SAME exception with the SAME message whether
    // the username doesn't exist at all, or it exists but the password is
    // wrong - see InvalidCredentialsException for why that matters.
    public String login(String username, String rawPassword) {
        Optional<User> user = userRepository.findByUsername(username);

        if (user.isEmpty() || !passwordEncoder.matches(rawPassword, user.get().getPassword())) {
            throw new InvalidCredentialsException("Invalid username or password.");
        }

        return jwtService.generateToken(username);
    }
}
