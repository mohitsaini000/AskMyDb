package AskMyDb.SpringAI.AskMyDb.exception;

// Thrown when the LLM's generated SQL fails our safety checks
// (not a SELECT, contains a destructive keyword, multiple statements, etc.)
// or when the model explicitly said it couldn't answer the question.
public class UnsafeSqlException extends RuntimeException {
    public UnsafeSqlException(String message) {
        super(message);
    }
}
