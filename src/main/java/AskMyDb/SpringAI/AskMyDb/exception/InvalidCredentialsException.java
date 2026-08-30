package AskMyDb.SpringAI.AskMyDb.exception;

// Thrown by AuthService.login when the username doesn't exist, or the
// password doesn't match the stored BCrypt hash. Deliberately the SAME
// exception (and same message) for both cases - see GlobalExceptionHandler
// for why: telling an attacker "that username doesn't exist" vs "wrong
// password" leaks which usernames are registered.
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
