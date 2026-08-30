package AskMyDb.SpringAI.AskMyDb.dto;

// What the client gets back after login, and again after a successful
// /api/auth/refresh call (same shape both times). "token" is the
// short-lived access token sent on every API call; "refreshToken" is only
// ever sent back to /api/auth/refresh or /api/auth/logout, never to a
// business endpoint like /api/ask.
public record LoginResponse(String token, String refreshToken) {
}
