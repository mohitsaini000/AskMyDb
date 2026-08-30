package AskMyDb.SpringAI.AskMyDb.dto;

// What the client actually gets back after a successful login - just the
// token. Everything the client needs (who they are, when it expires) is
// already encoded inside it.
public record LoginResponse(String token) {
}
