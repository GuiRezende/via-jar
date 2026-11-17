package br.edu.ifba.via.service;

import br.edu.ifba.via.dto.ConfiguracaoIa;
import br.edu.ifba.via.enums.ProvedorLlm;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;

// Centraliza a seleção dos provedores de inteligência artificial.
@Slf4j
@Service
public class SeletorLlmService {

    private final ObjectProvider<ChatClient> ollama;
    private final ObjectProvider<ChatClient> gemini;

    @Value("${via.ai.extractor:ollama}")
    private String extrator;

    @Value("${via.ai.inference:ollama}")
    private String inferencia;

    @Value("${via.ai.judge:ollama}")
    private String judge;

    private ConfiguracaoIa configuracaoPadrao;

    // Garante a injeção do cliente correspondente a cada provedor.
    public SeletorLlmService(
            @Qualifier("ollamaChatClient") ObjectProvider<ChatClient> ollama,
            @Qualifier("geminiChatClient") ObjectProvider<ChatClient> gemini) {

        this.ollama = ollama;
        this.gemini = gemini;
    }

    // Inicializa os provedores padrão definidos no YAML.
    @PostConstruct
    private void inicializarConfiguracao() {
        this.configuracaoPadrao = new ConfiguracaoIa(
                converterProvedor(extrator),
                converterProvedor(inferencia),
                converterProvedor(judge));
    }

    // Retorna o cliente correspondente ao provedor solicitado.
    public ChatClient selecionar(ProvedorLlm provedor) {

        log.info("Selecionando ChatClient: {}", provedor);

        return switch (provedor) {
            case OLLAMA -> ollama.getObject();
            case GEMINI -> gemini.getObject();
        };
    }

    // Preenche os provedores não informados com os valores padrão.
    public ConfiguracaoIa resolverConfiguracao(ConfiguracaoIa configuracao) {

        if (configuracao == null) {
            return configuracaoPadrao;
        }

        return new ConfiguracaoIa(
                obterProvedor(configuracao.extrator(), configuracaoPadrao.extrator()),
                obterProvedor(configuracao.inferencia(), configuracaoPadrao.inferencia()),
                obterProvedor(configuracao.judge(), configuracaoPadrao.judge()));
    }

    // Prioriza a configuração enviada pelo usuário.
    private ProvedorLlm obterProvedor(ProvedorLlm informado, ProvedorLlm padrao) {
        return informado != null ? informado : padrao;
    }

    // Normaliza os nomes dos provedores definidos no YAML.
    private ProvedorLlm converterProvedor(String provedor) {
        return ProvedorLlm.valueOf(provedor.strip().toUpperCase(Locale.ROOT));
    }
}