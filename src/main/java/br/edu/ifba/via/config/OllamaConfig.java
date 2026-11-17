package br.edu.ifba.via.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;

@Configuration
public class OllamaConfig {

    // Configura a comunicação HTTP com o servidor Ollama.
    @Bean
    public OllamaApi ollamaApi(@Value("${spring.ai.ollama.base-url}") String baseUrl) {
        return OllamaApi.builder().baseUrl(baseUrl).build();
    }

    // Inicializa o modelo Ollama com as propriedades definidas no YAML.
    @Bean
    @Primary
    public OllamaChatModel ollamaChatModel(
            OllamaApi ollamaApi,
            @Value("${spring.ai.ollama.chat.model}") String modelo,
            @Value("${spring.ai.ollama.chat.temperature:0.1}") double temperatura,
            @Value("${spring.ai.ollama.chat.num-predict:4096}") int limiteTokens) {

        // Define os parâmetros utilizados durante a inferência.
        OllamaChatOptions opcoes = OllamaChatOptions.builder()
                .model(modelo)
                .temperature(temperatura)
                .numPredict(limiteTokens)
                .disableThinking()
                .build();

        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(opcoes)
                .build();
    }

    // Preserva a compatibilidade com o construtor atual do ChatService.
    @Bean
    @Primary
    @Scope("prototype")
    public ChatClient.Builder chatClientBuilder(OllamaChatModel modelo) {
        return ChatClient.builder(modelo);
    }

    // Disponibiliza o cliente Ollama para seleção dinâmica.
    @Bean
    @Primary
    public ChatClient ollamaChatClient(OllamaChatModel modelo) {
        return ChatClient.create(modelo);
    }
}