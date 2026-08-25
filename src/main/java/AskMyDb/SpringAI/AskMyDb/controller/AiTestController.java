package AskMyDb.SpringAI.AskMyDb.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiTestController {

    private final ChatClient chatClient;

    // Constructor injection: Spring hands us the ChatClient bean we defined in AiConfig.
    public AiTestController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    // Simple sanity-check endpoint: proves Spring Boot <-> Ollama wiring actually works,
    // before we add any database/SQL-generation logic on top of it.
    // Try: GET http://localhost:8080/api/test-ai?message=Hello, who are you?
    @GetMapping("/api/test-ai")
    public String testAi(@RequestParam(defaultValue = "Say hello in one short sentence.") String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }
}
