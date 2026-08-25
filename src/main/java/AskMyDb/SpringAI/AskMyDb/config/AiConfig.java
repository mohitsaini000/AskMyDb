package AskMyDb.SpringAI.AskMyDb.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    // Spring Boot auto-configures a ChatClient.Builder bean for us
    // (because of the spring-ai-starter-model-ollama dependency + application.yaml config).
    // We just take that builder and build one shared ChatClient bean from it,
    // so the rest of the app can simply @Autowired a ChatClient wherever needed.
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
