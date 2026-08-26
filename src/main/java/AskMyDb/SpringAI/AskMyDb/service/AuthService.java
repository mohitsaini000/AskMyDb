package AskMyDb.SpringAI.AskMyDb.service;

import AskMyDb.SpringAI.AskMyDb.model.User;
import AskMyDb.SpringAI.AskMyDb.repository.UserRepository;
import AskMyDb.SpringAI.AskMyDb.exception.UsernameTakenException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
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
}
