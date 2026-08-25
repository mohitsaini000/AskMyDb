package AskMyDb.SpringAI.AskMyDb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// What the client sends us. Validation happens here, BEFORE we ever touch
// the LLM or the database - rejecting garbage early saves a wasted AI call.
public record AskRequest(

        @NotBlank(message = "question must not be blank")
        @Size(max = 500, message = "question must be at most 500 characters")
        String question

) {}
