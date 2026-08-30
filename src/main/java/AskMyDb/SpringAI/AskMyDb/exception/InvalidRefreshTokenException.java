package AskMyDb.SpringAI.AskMyDb.exception;

// Thrown when a refresh token doesn't exist, was already used/revoked, or
// has expired. All three cases get the same generic message and status -
// a client trying to refresh has no legitimate need to know which.
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
