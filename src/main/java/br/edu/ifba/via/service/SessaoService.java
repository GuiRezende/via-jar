package br.edu.ifba.via.service;

import br.edu.ifba.via.dto.ChatResponse;
import br.edu.ifba.via.dto.ConfiguracaoIa;
import br.edu.ifba.via.service.AvaliadorRoteiroService.MensagemConversa;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static br.edu.ifba.via.helper.ViagemHelper.*;

// Gerencia as sessões e o histórico; a orquestração continua no ChatService.
@Service
public class SessaoService {
    private static final long TEMPO_EXPIRACAO_SESSAO_SEGUNDOS = 3600;
    private static final String DESTINO_RECOMENDADO = "destinoRecomendado";
    private final ObjectMapper conversorJson = new ObjectMapper();
    private final Map<String, Sessao> sessoes = new ConcurrentHashMap<>();

    Sessao obterSessao(String id) {
        return sessoes.computeIfAbsent(id, chave -> new Sessao());
    }

    // Chamado pelo ChatService dentro do bloco synchronized da sessão.
    void iniciarRequisicao(Sessao sessao, ConfiguracaoIa configuracao, String mensagem) {
        // A aprovação anterior não deve ser reaproveitada após uma troca de Judge.
        if (sessao.configuracaoIa != null && sessao.configuracaoIa.judge() != configuracao.judge()) {
            sessao.dadosValidados = null;
        }
        sessao.configuracaoIa = configuracao;
        sessao.atualizadaEm = Instant.now();
        sessao.historico.add(new MensagemConversa("usuario", mensagem));
        sessao.omitirRespostaDoContexto = false;
    }

    // Mantém a expiração por inatividade de uma hora, como no fluxo original.
    void removerSessoesExpiradas() {
        sessoes.entrySet().removeIf(entrada ->
                entrada.getValue().atualizadaEm.plusSeconds(TEMPO_EXPIRACAO_SESSAO_SEGUNDOS).isBefore(Instant.now()));
    }

    String resolverIdentificadorSessao(String id) {
        return id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
    }

    // Registra também o roteiro entregue, permitindo interpretar pedidos de revisão.
    void registrarRespostaHistorico(Sessao sessao, ChatResponse resposta) {
        String conteudo = resposta.resposta();
        if (resposta.roteiro() != null) conteudo += "\nRoteiro:\n" + resposta.roteiro();
        if (resposta.orcamentoSugerido() != null) conteudo += "\nOrçamento sugerido: " + resposta.orcamentoSugerido();
        sessao.historico.add(new MensagemConversa("assistente", conteudo));
        int indice = sessao.historico.size() - 1;
        if (sessao.omitirRespostaDoContexto) sessao.indicesForaDoContexto.add(indice);
        else if (!sessao.candidatosPendentes.isEmpty() && resposta.roteiro() == null)
            sessao.indicesOfertasPendentes.add(indice);
    }

    // Preserva o histórico original e separa mensagens operacionais da intenção da viagem.
    List<MensagemConversa> historicoIntencao(Sessao sessao) {
        List<MensagemConversa> historico = new ArrayList<>();
        for (int i = 0; i < sessao.historico.size(); i++) {
            if (!sessao.indicesForaDoContexto.contains(i)) historico.add(sessao.selecoesInterpretadas.getOrDefault(i, sessao.historico.get(i)));
        }
        return List.copyOf(historico);
    }

    // Inclui as respostas locais ainda não interpretadas e a pergunta anterior à mensagem atual.
    List<MensagemConversa> historicoNaoExtraido(Sessao sessao) {
        List<MensagemConversa> historico = new ArrayList<>();
        for (int i = sessao.indiceUltimaExtracao + 1; i < sessao.historico.size() - 1; i++) {
            if (!sessao.indicesForaDoContexto.contains(i)) historico.add(sessao.historico.get(i));
        }
        return List.copyOf(historico);
    }

    // Uma rejeição isolada do cache não muda os requisitos já informados.
    void registrarRejeicaoNoContexto(Sessao sessao) {
        descartarOfertasDoContexto(sessao);
        sessao.indicesForaDoContexto.add(sessao.historico.size() - 1);
    }

    void descartarOfertasDoContexto(Sessao sessao) {
        sessao.indicesForaDoContexto.addAll(sessao.indicesOfertasPendentes);
        sessao.indicesOfertasPendentes.clear();
    }

    // A delegação pertence ao estado da sessão, não aos dados persistidos do roteiro.
    boolean atualizarDadosSessao(Sessao sessao, Map<String, Object> dadosExtraidos) {
        Map<String, Object> dadosAnteriores = Map.copyOf(sessao.dados);
        boolean delegacaoAnterior = sessao.destinoDelegado;
        boolean recomendacaoAnterior = sessao.destinoRecomendado;
        Map<String, Object> alteracoes = new HashMap<>(dadosExtraidos);
        Object delegacao = alteracoes.remove(DESTINO_DELEGADO);
        if (Boolean.TRUE.equals(delegacao) && !alteracoes.containsKey(DESTINO)) {
            sessao.destinoDelegado = true;
            sessao.destinoPerguntado = true;
            sessao.destinoRecomendado = false;
            sessao.dados.remove(DESTINO);
        } else if (alteracoes.containsKey(DESTINO)) {
            sessao.destinoDelegado = false;
            sessao.destinoPerguntado = true;
            sessao.destinoRecomendado = false;
        }
        sessao.dados.putAll(alteracoes);
        sessao.campoPendente = null;
        return delegacaoAnterior != sessao.destinoDelegado || recomendacaoAnterior != sessao.destinoRecomendado
                || !dadosAnteriores.keySet().equals(sessao.dados.keySet())
                || alteracoes.entrySet().stream().anyMatch(entrada -> !valoresEquivalentes(entrada.getValue(), dadosAnteriores.get(entrada.getKey())));
    }

    // Restaura os tipos usados durante a coleta dos dados da viagem.
    void restaurarDadosSessao(Sessao sessao, Map<String, String> dados) {
        sessao.destinoRecomendado = sessao.destinoRecomendado && valoresEquivalentes(sessao.dados.get(DESTINO), dados.get(DESTINO));
        sessao.dados.clear();
        sessao.destinoDelegado = false;
        sessao.destinoPerguntado = true;
        dados.forEach((chave, valor) -> {
            if (!valor.isBlank()) {
                sessao.dados.put(chave, converterValorArmazenado(chave, valor));
            }
        });
    }

    Object converterValorArmazenado(String chave, String valor) {
        return switch (chave) {
            case ORCAMENTO -> new BigDecimal(valor);
            case DURACAO, VIAJANTES -> Integer.parseInt(valor);
            default -> valor;
        };
    }

    // Compara números pelo valor e textos sem distinguir maiúsculas ou espaços externos.
    static boolean valoresEquivalentes(Object primeiro, Object segundo) {
        if (primeiro == null || segundo == null) return primeiro == segundo;
        try {
            return new BigDecimal(primeiro.toString()).compareTo(new BigDecimal(segundo.toString())) == 0;
        } catch (NumberFormatException ignorado) {
            return primeiro.toString().strip().equalsIgnoreCase(segundo.toString().strip());
        }
    }

    // Mantém a intenção original e as respostas curtas no contexto de geração.
    String montarContextoGeracao(Sessao sessao, String mensagem) {
        try {
            return conversorJson.writeValueAsString(Map.of("historico", historicoIntencao(sessao), "dadosViagem", sessao.dados, "mensagemAtual", mensagem, "gerarNovoRoteiro", sessao.ignorarCache, DESTINOS_REJEITADOS, Set.copyOf(sessao.destinosRejeitados)));
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível montar o contexto da viagem", e);
        }
    }

    // A procedência do destino é metadado de avaliação, não requisito persistido da viagem.
    Map<String, Object> montarDadosAvaliacao(Sessao sessao, Map<String, Object> dados) {
        Map<String, Object> contexto = new HashMap<>(dados);
        contexto.put(DESTINO_RECOMENDADO, sessao.destinoRecomendado && valoresEquivalentes(dados.get(DESTINO), sessao.dados.get(DESTINO)));
        contexto.put(DESTINO_DELEGADO, sessao.destinoDelegado);
        return Map.copyOf(contexto);
    }

}
