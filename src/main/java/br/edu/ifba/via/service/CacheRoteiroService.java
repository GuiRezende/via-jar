package br.edu.ifba.via.service;

import br.edu.ifba.via.dto.ChatResponse;
import br.edu.ifba.via.helper.ViagemHelper;
import br.edu.ifba.via.model.ContadorTokens;
import br.edu.ifba.via.model.PlanoArmazenado;
import br.edu.ifba.via.model.RegistroAvaliacao;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.*;

import static br.edu.ifba.via.helper.ViagemHelper.*;
import static br.edu.ifba.via.service.SessaoService.valoresEquivalentes;

@Slf4j
@Service
@RequiredArgsConstructor
public class CacheRoteiroService {

    private final CacheSemantico cacheSemantico;
    private final BaseConhecimento baseConhecimento;
    private final AvaliadorRoteiroService avaliadorRoteiro;
    private final LlmService llmService;
    private final SessaoService sessaoService;
    private final HistoricoAvaliacaoService historicoAvaliacaoService;
    private final ObjectMapper conversorJson = new ObjectMapper();

    // Trata uma oferta pendente; vazio indica que a conversa deve prosseguir.
    Optional<ChatResponse> processarCandidatosPendentes(String id, String mensagem, Sessao sessao, boolean dadosAlterados, ContadorTokens tokens) {
        if (sessao.candidatosPendentes.isEmpty()) {
            return Optional.empty();
        }
        // Alterar dados invalida as ofertas, mas não equivale a rejeitá-las.
        if (dadosAlterados) {
            sessao.candidatosPendentes = List.of();
            return Optional.empty();
        }
        if (ViagemHelper.rejeicao(mensagem)) {
            rejeitarCandidatos(sessao);
            sessao.ignorarCache = true;
            return Optional.empty();
        }
        if (ViagemHelper.personalizacao(mensagem)) {
            rejeitarCandidatos(sessao);
            sessao.ignorarCache = true;
            return Optional.of(montarRespostaLocal(id, baseConhecimento.redenrizarTemplate("cache_personalizar", Map.of()), sessao, tokens));
        }

        int indiceSelecionado = ViagemHelper.identificarOpcaoSelecionada(mensagem, sessao.candidatosPendentes.size());
        if (indiceSelecionado >= 0) {
            return reutilizarPlanejamento(id, sessao, sessao.candidatosPendentes.get(indiceSelecionado), tokens);
        }
        return Optional.of(montarRespostaLocal(id, montarOfertaCandidatos(sessao), sessao, tokens));
    }

    // Evita reapresentar candidatos explicitamente rejeitados nesta sessão.
    void rejeitarCandidatos(Sessao sessao) {
        sessaoService.descartarOfertasDoContexto(sessao);
        sessao.candidatosPendentes.forEach(candidato -> sessao.assinaturasRejeitadas.add(candidato.signature()));
        sessao.candidatosPendentes = List.of();
    }

    // Preserva a falha explícita de persistência do fluxo original.
    void armazenarPlanejamento(String mensagem, String assinatura, PlanoArmazenado plano) {
        try {
            cacheSemantico.armazenarResposta(mensagem, assinatura, conversorJson.writeValueAsString(plano));
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível armazenar planejamento", e);
        }
    }

    // Nunca oferece um roteiro que contradiga dados explícitos já informados.
    private boolean candidatoCompativelComSessao(Sessao sessao, CacheSemantico.Candidate candidato) {
        for (String campo : CAMPOS_VIAGEM) {
            Object valor = sessao.dados.get(campo);
            if (valor != null && !valor.toString().isBlank() && !valoresEquivalentes(valor, candidato.data().get(campo)))
                return false;
        }
        return true;
    }

    // Busca até 100 candidatos e oferece no máximo três opções válidas e distintas.
    boolean oferecerCandidatos(String mensagem, Sessao sessao) {
        Object origem = sessao.dados.get(ORIGEM);
        Object destino = sessao.dados.get(DESTINO);
        if (sessao.ignorarCache || origem == null || destino == null) return false;
        Set<String> assinaturasProcessadas = new HashSet<>();
        sessao.candidatosPendentes = cacheSemantico.buscarRoteirosPorTrajeto(mensagem, origem.toString(), destino.toString(), Map.copyOf(sessao.dados), this::planoArmazenadoValido, 100)
                .stream().filter(candidato -> !sessao.assinaturasRejeitadas.contains(candidato.signature()) && candidatoCompativelComSessao(sessao, candidato) && candidatoElegivel(candidato) && assinaturasProcessadas.add(candidato.signature()))
                .limit(3).toList();
        return !sessao.candidatosPendentes.isEmpty();
    }

    // Descarta candidatos incompletos, inválidos ou com orçamento inconsistente.
    private boolean candidatoElegivel(CacheSemantico.Candidate candidato) {
        if (!possuiDadosObrigatoriosCandidato(candidato.data())) {
            return false;
        }
        try {
            BigDecimal total = new BigDecimal(candidato.data().get(ORCAMENTO));
            if (total.signum() <= 0 ||
                    Integer.parseInt(candidato.data().get(DURACAO)) <= 0 ||
                    Integer.parseInt(candidato.data().get(VIAJANTES)) <= 0) {
                return false;
            }
            PlanoArmazenado plano = conversorJson.readValue(candidato.answer(), PlanoArmazenado.class);
            return possuiOrcamentoEDestinoCompativeis(candidato, plano, total);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean possuiDadosObrigatoriosCandidato(Map<String, String> dados) {
        for (String campo : CAMPOS_OBRIGATORIOS) {
            if (dados.getOrDefault(campo, "").isBlank()) {
                return false;
            }
        }
        return !dados.getOrDefault(DESTINO, "").isBlank();
    }

    // Verifica a identidade do destino e a soma das parcelas do orçamento.
    private boolean possuiOrcamentoEDestinoCompativeis(CacheSemantico.Candidate candidato, PlanoArmazenado plano, BigDecimal total) {
        if (!plano.destino().equalsIgnoreCase(candidato.data().get(DESTINO))) {
            return false;
        }
        BigDecimal valorDistribuido = plano.orcamentoSugerido().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return valorDistribuido.compareTo(total.setScale(2, RoundingMode.HALF_UP)) == 0;
    }

    // Apresenta as opções e explicita diferenças antes da confirmação.
    String montarOfertaCandidatos(Sessao sessao) {
        List<String> linhas = new ArrayList<>();
        boolean multiplosCandidatos = sessao.candidatosPendentes.size() > 1;
        for (int i = 0; i < sessao.candidatosPendentes.size(); i++) {
            linhas.add(formatarCandidato(sessao, i, multiplosCandidatos));
        }
        return String.join("\n", linhas) + "\n" + montarPerguntaConfirmacao(sessao, multiplosCandidatos);
    }

    private String formatarCandidato(Sessao sessao, int indice, boolean multiplosCandidatos) {
        var candidato = sessao.candidatosPendentes.get(indice);
        Map<String, Object> valores = new HashMap<>(candidato.data());
        valores.put(ORCAMENTO, formatarOrcamento(candidato.data()));
        String resumo = baseConhecimento.redenrizarTemplate("cache_roteiro_encontrado", valores);
        String diferencas = compararDadosViagem(sessao.dados, candidato.data());
        return (multiplosCandidatos ? (indice + 1) + ". " : "") + resumo
                + (diferencas.isBlank() ? "" : " Diferenças: " + diferencas + ".");
    }

    private String montarPerguntaConfirmacao(Sessao sessao, boolean multiplosCandidatos) {
        if (multiplosCandidatos) {
            return "Qual opção deseja reutilizar? Responda com o número ou diga que prefere criar um novo roteiro.";
        }
        if (sessao.candidatosPendentes.size() == 1
                && !compararDadosViagem(sessao.dados, sessao.candidatosPendentes.getFirst().data()).isBlank()) {
            return "Se reutilizar, usarei os dados salvos acima. Confirma? Responda sim ou não.";
        }
        return "Deseja reutilizar esse roteiro? Responda sim ou não.";
    }

    // Formata o valor na moeda salva; usa representação simples em caso de falha.
    private static String formatarOrcamento(Map<String, String> dados) {
        try {
            NumberFormat formato = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"));
            formato.setCurrency(Currency.getInstance(dados.get(MOEDA)));
            return formato.format(new BigDecimal(dados.get(ORCAMENTO)));
        } catch (Exception e) {
            return dados.get(ORCAMENTO) + " " + dados.get(MOEDA);
        }
    }

    // Compara somente os campos relevantes já informados pelo usuário.
    private static String compararDadosViagem(Map<String, Object> dadosAtuais, Map<String, String> dadosSalvos) {
        List<String> diferencas = new ArrayList<>();
        for (String chave : List.of(PERIODO, DURACAO, VIAJANTES, ORCAMENTO, MOEDA, PREFERENCIAS, RESTRICOES)) {
            Object valorInformado = dadosAtuais.get(chave);
            if (valorInformado != null && !valorInformado.toString().isBlank() && !valoresEquivalentes(valorInformado, dadosSalvos.get(chave)))
                diferencas.add(obterRotuloCampo(chave) + " informado: " + valorInformado + "; salvo: " + dadosSalvos.getOrDefault(chave, "não informado"));
        }
        return String.join("; ", diferencas);
    }

    private static String obterRotuloCampo(String chave) {
        return switch (chave) {
            case PERIODO -> "período";
            case DURACAO -> "duração";
            case ORCAMENTO -> "orçamento";
            case PREFERENCIAS -> "preferências";
            case RESTRICOES -> "restrições";
            default -> chave;
        };
    }

    // Reutiliza o roteiro e substitui o contexto pelos dados confirmados do cache.
    private Optional<ChatResponse> reutilizarPlanejamento(String id, Sessao sessao, CacheSemantico.Candidate candidato, ContadorTokens tokens) {
        log.info("Judge - Reavaliação do cache: {}", sessao.configuracaoIa.judge());
        PlanoArmazenado dadosSalvos;
        try {
            dadosSalvos = conversorJson.readValue(candidato.answer(), PlanoArmazenado.class);
        } catch (Exception e) {
            throw new IllegalStateException("Planejamento em cache inválido", e);
        }
        // Avalia o candidato com os dados que seriam efetivamente reutilizados,
        // sem substituir o contexto atual antes da aprovação.
        Map<String, Object> dadosCandidato = new HashMap<>();
        candidato.data().forEach((chave, valor) -> {
            if (!valor.isBlank()) dadosCandidato.put(chave, sessaoService.converterValorArmazenado(chave, valor));
        });
        var avaliacao = reavaliarERegistrar(id, sessao, candidato.signature(), dadosCandidato, dadosSalvos, tokens);

        if (!avaliacao.aprovado()) {
            rejeitarCandidatos(sessao);
            sessao.ignorarCache = true;
            if (avaliacao.status() == AvaliadorRoteiroService.Status.INDISPONIVEL) {
                sessao.omitirRespostaDoContexto = true;
                return Optional.of(montarRespostaLocal(id, "Não consegui reavaliar o roteiro do cache. Solicite uma nova tentativa.", sessao, tokens));
            }
            // O fluxo normal coleta os dados faltantes ou gera um novo roteiro.
            return Optional.empty();
        }

        sessaoService.restaurarDadosSessao(sessao, candidato.data());
        sessao.dadosValidados = Map.copyOf(sessao.dados);
        sessao.indicesOfertasPendentes.clear();
        sessao.assinaturasRejeitadas.add(candidato.signature());
        sessao.candidatosPendentes = List.of();
        return Optional.of(new ChatResponse(id, dadosSalvos.resposta(), "HIT", "CACHE", Map.copyOf(sessao.dados),
                dadosSalvos.roteiro(), dadosSalvos.orcamentoSugerido(), dadosSalvos.fontes(), tokens.obterConsumo(), avaliacao.nota()));
    }

    // Registra a reavaliação antes de modificar a sessão, inclusive quando o Judge reprova.
    private AvaliadorRoteiroService.ResultadoAvaliacao reavaliarERegistrar(String id, Sessao sessao, String assinatura,
            Map<String, Object> dadosCandidato, PlanoArmazenado plano, ContadorTokens tokens) {
        UUID idProcessamento = UUID.randomUUID();
        var historico = sessaoService.historicoIntencao(sessao);
        var dadosAvaliacao = sessaoService.montarDadosAvaliacao(sessao, dadosCandidato);
        ContadorTokens tokensTentativa = tokens.criarContadorParcial();
        List<String> respostasJudge = new ArrayList<>();
        long inicio = System.nanoTime();
        var avaliacao = avaliadorRoteiro.avaliarRoteiro(historico, dadosAvaliacao, plano.roteiro(),
                plano.orcamentoSugerido(), plano.fontes(), (sistema, usuario) -> {
                    String resposta = llmService.consultarLlm(sessao.configuracaoIa.judge(), sistema, usuario, tokensTentativa);
                    if (resposta != null) respostasJudge.add(resposta);
                    return resposta;
                });
        // Não houve geração nesta requisição; a resposta bruta do gerador permanece nula.
        var registro = new RegistroAvaliacao(UUID.randomUUID(), idProcessamento, id, Instant.now(),
                RegistroAvaliacao.OrigemAvaliacao.CACHE, 1, sessao.configuracaoIa, historico, dadosAvaliacao,
                plano.roteiro(), null, plano.orcamentoSugerido(), plano.fontes(), avaliacao, respostasJudge,
                tokensTentativa.obterConsumo(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio), assinatura);
        historicoAvaliacaoService.registrar(registro);
        log.info("Reavaliação do cache registrada: {} | Status: {} | Nota: {} | Motivos: {}",
                registro.id(), avaliacao.status(), avaliacao.nota(), avaliacao.motivos());
        return avaliacao;
    }

    // Mantém a ordem e a normalização da assinatura persistida no cache.
    String gerarAssinatura(Map<String, Object> dados) {
        List<String> partes = new ArrayList<>();
        for (String chave : List.of(ORCAMENTO, MOEDA, PERIODO, VIAJANTES, RESTRICOES, PREFERENCIAS, ORIGEM, DESTINO, DURACAO)) {
            Object valor = dados.getOrDefault(chave, "");
            if (chave.equals(ORCAMENTO) && valor instanceof BigDecimal quantia)
                valor = quantia.stripTrailingZeros().toPlainString();
            partes.add(chave + "=" + valor);
        }
        return "|" + String.join("|", partes);
    }

    // Valida a estrutura e a versão do planejamento armazenado.
    private boolean planoArmazenadoValido(String valor) {
        try {
            var noJson = conversorJson.readTree(valor);
            return PlanoArmazenado.VERSAO_FORMATO_PLANO.equals(noJson.path("formato").asText()) &&
                    !noJson.path(DESTINO).asText().isBlank() && !noJson.path("roteiro").asText().isBlank() &&
                    noJson.path("orcamentoSugerido").isObject() && noJson.path("fontes").isArray();
        } catch (Exception e) {
            return false;
        }
    }

    // Resposta local de coleta ou confirmação, sem mudar o contrato do DTO.
    private ChatResponse montarRespostaLocal(String id, String resposta, Sessao sessao, ContadorTokens tokens) {
        return new ChatResponse(id, resposta, "NAO_APLICAVEL", "COLETA_DADOS", Map.copyOf(sessao.dados), null, null, null, tokens.obterConsumo(), null);
    }
}
