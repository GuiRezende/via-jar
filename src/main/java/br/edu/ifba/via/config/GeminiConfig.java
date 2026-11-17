package br.edu.ifba.via.config;

import com.google.genai.Client;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class GeminiConfig {

    // Configura o modelo Gemini independentemente da autoconfiguração do Ollama.
    // A inicialização sob demanda permite utilizar a aplicação sem uma chave do Google.
    @Bean
    @Lazy
    public GoogleGenAiChatModel geminiChatModel(
            @Value("${spring.ai.google.genai.api-key}") String apiKey,
            @Value("${spring.ai.google.genai.chat.model}") String modelo,
            @Value("${spring.ai.google.genai.chat.temperature:0.1}") double temperatura) {

        // Valida a credencial antes de inicializar o modelo.
        if (apiKey.isBlank()) {
            throw new IllegalStateException("GOOGLE_API_KEY não configurada");
        }

        // Cria o modelo utilizando as propriedades do application-gemini.yml.
        return GoogleGenAiChatModel.builder()
                .genAiClient(Client.builder().apiKey(apiKey).build())
                .options(GoogleGenAiChatOptions.builder().model(modelo).temperature(temperatura).build())
                .build();
    }

    // Disponibiliza um cliente exclusivo para comunicação com o Gemini.
    @Bean
    @Lazy
    public ChatClient geminiChatClient(GoogleGenAiChatModel modelo) {
        return ChatClient.create(modelo);
    }
}