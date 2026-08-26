package AskMyDb.SpringAI.AskMyDb.service;

// Thrown when a registration attempt uses a username that already exists.
// Its own type (instead of a generic exception) lets AuthController map it
// to a specific, correct HTTP status (409 Conflict) - the same pattern
// UnsafeSqlException uses for the ask pipeline.
public class UsernameTakenException extends RuntimeException {
    public UsernameTakenException(String message) {
        super(message);
    }
}
