package br.edu.ifba.via.service;

import br.edu.ifba.via.dto.ConfiguracaoIa;
import br.edu.ifba.via.enums.ProvedorLlm;
import br.edu.ifba.via.model.ContadorTokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {
    private final SeletorLlmService seletorLlm;

    // Resolve a configuração dos provedores para a requisição atual.
    public ConfiguracaoIa resolverConfiguracao(ConfiguracaoIa configuracao) {
        return seletorLlm.resolverConfiguracao(configuracao);
    }

    // Executa a chamada à LLM e registra o consumo informado pelo provedor.
    public String consultarLlm(ProvedorLlm provedor, String instrucaoSistema, String mensagemUsuario, ContadorTokens tokens) {
        ChatClient clienteLlm = seletorLlm.selecionar(provedor);
        org.springframework.ai.chat.model.ChatResponse respostaLlm = clienteLlm.prompt().system(instrucaoSistema).user(mensagemUsuario).call().chatResponse();
        if (respostaLlm == null || respostaLlm.getResult() == null || respostaLlm.getResult().getOutput() == null)
            throw new IllegalStateException("LLM não retornou texto");
        Usage consumo = respostaLlm.getMetadata() == null ? null : respostaLlm.getMetadata().getUsage();
        tokens.acumular(consumo);
        var metadados = respostaLlm.getResult().getMetadata();
        log.info("LLM - Provedor: {} | FinishReason: {}", provedor, metadados == null ? null : metadados.getFinishReason());
        String resposta = respostaLlm.getResult().getOutput().getText();

        log.info("LLM - Resposta bruta:\n{}", resposta);
        log.info("LLM - Consumo: {}", consumo);
        log.info("LLM - Tamanho da resposta: {}",
                resposta == null ? 0 : resposta.length());

        return resposta;
    }
}
