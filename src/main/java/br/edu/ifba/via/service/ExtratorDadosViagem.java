package br.edu.ifba.via.service;

import br.edu.ifba.via.dto.DadosViagemExtraidos;
import br.edu.ifba.via.helper.TextoHelper;
import br.edu.ifba.via.service.AvaliadorRoteiroService.MensagemConversa;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import static br.edu.ifba.via.helper.ViagemHelper.*;

// Responsável pela extração semântica e validação dos dados da viagem.
@Slf4j
final class ExtratorDadosViagem {

    private static final Set<String> MOEDAS_SUPORTADAS = Set.of("BRL", "USD", "EUR");
    private static final String CAMINHO_PROMPT = "prompt-extrator.md";
    private final ObjectMapper conversorJson = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private final String promptExtrator = carregarPrompt();


    // Interpreta a mensagem e as interações locais ainda não extraídas.
    Map<String, Object> extrairDados(String mensagem, String campoPendente, Map<String, Object> dadosAtuais,
                                     List<MensagemConversa> historico , boolean destinoDelegado, boolean permitirExtracaoLocal, BiFunction<String, String, String> consultarLlm) {

        // Prioriza a extração determinística quando não há informações anteriores pendentes.
        if (permitirExtracaoLocal && historico.stream().noneMatch(item -> "usuario".equals(item.papel()))) {
            Map<String, Object> dadosLocais = extrairDadosLocais(mensagem, campoPendente);

            if (!dadosLocais.isEmpty()) {
                log.info("Extração local - Campo: {} | Sem consulta à LLM", campoPendente);
                return dadosLocais;
            }
        }
        try {
            Map<String, Object> contexto = new HashMap<>();
            contexto.put("mensagem", mensagem);
            contexto.put("campoPendente", campoPendente);
            contexto.put("dadosAtuais", dadosAtuais);
            contexto.put("historico", historico);
            contexto.put(DESTINO_DELEGADO, destinoDelegado);

            String resposta = consultarLlm.apply(promptExtrator, conversorJson.writeValueAsString(contexto));
            if (resposta == null || resposta.isBlank()) throw new IllegalStateException("O extrator não retornou dados");

            log.debug("Extrator - Resposta recebida: {}", resposta);

            var json = conversorJson.readTree(TextoHelper.normalizarJson(resposta));
            if (!(json instanceof ObjectNode objeto)) throw new IllegalStateException("Resposta inválida do extrator");

            // A delegação é metadado de controle, não um campo do DTO da viagem.
            JsonNode delegacao = objeto.remove(DESTINO_DELEGADO);
            if (delegacao != null && !delegacao.isNull() && !delegacao.isBoolean()) {
                throw new IllegalStateException("Delegação do destino inválida");
            }
            DadosViagemExtraidos dados = conversorJson.treeToValue(objeto, DadosViagemExtraidos.class);
            Map<String, Object> resultado = validarDadosExtraidos(dados);
            if (delegacao != null && delegacao.isBoolean() && delegacao.booleanValue() && !resultado.containsKey(DESTINO)) {
                resultado.put(DESTINO_DELEGADO, true);
            }
            return resultado;
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível realizar a extração semântica", e);
        }
    }

    // Mantém compatibilidade com as chamadas anteriores.
    Map<String, Object> extrairDados(String mensagem, String campoPendente, Map<String, Object> dadosAtuais, BiFunction<String, String, String> consultarLlm) {
        return extrairDados(mensagem, campoPendente, dadosAtuais, List.of(), false, true, consultarLlm);
    }

    Map<String, Object> extrairDados(String mensagem, String campoPendente, Map<String, Object> dadosAtuais, List<MensagemConversa> historico, boolean destinoDelegado, BiFunction<String, String, String> consultarLlm) {
        return extrairDados(mensagem, campoPendente, dadosAtuais, historico, destinoDelegado, true, consultarLlm);
    }


    // Valida os dados antes de incorporá-los ao contexto da sessão.
    private Map<String, Object> validarDadosExtraidos(DadosViagemExtraidos dados) {
        Map<String, Object> resultado = new HashMap<>();
        adicionarTexto(resultado, ORIGEM, dados.origem());
        adicionarTexto(resultado, DESTINO, dados.destino());
        adicionarTexto(resultado, PERIODO, dados.periodo());
        adicionarTexto(resultado, PREFERENCIAS, dados.preferencias());
        adicionarTexto(resultado, RESTRICOES, dados.restricoes());
        adicionarInteiroPositivo(resultado, DURACAO, dados.duracao());
        adicionarInteiroPositivo(resultado, VIAJANTES, dados.viajantes());
        if (dados.orcamento() != null && dados.orcamento().signum() > 0) resultado.put(ORCAMENTO, dados.orcamento());
        if (dados.moeda() != null) {
            String moeda = dados.moeda().strip().toUpperCase(Locale.ROOT);
            if (MOEDAS_SUPORTADAS.contains(moeda)) resultado.put(MOEDA, moeda);
        }
        return resultado;
    }

    private void adicionarTexto(Map<String, Object> dados, String campo, String valor) {
        if (valor != null && !valor.isBlank()) dados.put(campo, valor.strip());
    }

    private void adicionarInteiroPositivo(Map<String, Object> dados, String campo, Integer valor) {
        if (valor != null && valor > 0) dados.put(campo, valor);
    }

    private String carregarPrompt() {
        try {
            return new ClassPathResource(CAMINHO_PROMPT).getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Prompt do extrator não encontrado", e);
        }
    }
}
