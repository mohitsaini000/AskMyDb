package AskMyDb.SpringAI.AskMyDb.dto;

import jakarta.validation.constraints.NotBlank;

// Used by both /api/auth/refresh (get a new access token) and
// /api/auth/logout (revoke this refresh token) - same shape, different
// endpoints.
public record RefreshRequest(@NotBlank String refreshToken) {
}
