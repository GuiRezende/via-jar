package br.edu.ifba.via.service;

import br.edu.ifba.via.dto.*;
import br.edu.ifba.via.model.ContadorTokens;
import br.edu.ifba.via.model.PlanoArmazenado;
import br.edu.ifba.via.model.ResultadoPlanejamento;
import br.edu.ifba.via.helper.ViagemHelper;
import br.edu.ifba.via.service.AvaliadorRoteiroService.MensagemConversa;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

import static br.edu.ifba.via.helper.ViagemHelper.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final LlmService llmService;
    private final CacheRoteiroService cacheRoteiroService;
    private final BaseConhecimento baseConhecimento;
    private final AvaliadorRoteiroService avaliadorRoteiro;
    private final ExtratorDadosViagem extratorDadosViagem = new ExtratorDadosViagem();
    private final SessaoService sessaoService;
    private final DestinoService destinoService;
    private final PlanejamentoService planejamentoService;

    // Valida a entrada e serializa o processamento de uma mesma sessão.
    public ChatResponse responderMensagem(ChatRequest requisicao) {
        validarMensagem(requisicao.mensagem());
        sessaoService.removerSessoesExpiradas();
        String id = sessaoService.resolverIdentificadorSessao(requisicao.sessao());
        Sessao sessao = sessaoService.obterSessao(id);

        synchronized (sessao) {
            ContadorTokens tokens = new ContadorTokens();
            // Resolve a configuração a cada requisição, sem alterar outras sessões.
            ConfiguracaoIa configuracao = llmService.resolverConfiguracao(requisicao.configuracaoIa());
            sessaoService.iniciarRequisicao(sessao, configuracao, requisicao.mensagem());
            ChatResponse resposta = processarMensagem(id, requisicao.mensagem(), sessao, tokens);
            sessaoService.registrarRespostaHistorico(sessao, resposta);
            return resposta;
        }
    }

    private void validarMensagem(String mensagem) {
        if (mensagem == null || mensagem.isBlank()) {
            throw new IllegalArgumentException("Mensagem obrigatória");
        }
    }

    // Coordena a conversa na ordem original de prioridade das respostas.
    private ChatResponse processarMensagem(String id, String mensagem, Sessao sessao, ContadorTokens tokens) {
        Optional<ChatResponse> escolhaDestino = processarDestinosPendentes(id, mensagem, sessao, tokens);

        if (escolhaDestino.isPresent()) return escolhaDestino.get();

        // Uma nova recomendação preserva os requisitos e não apaga o cache compartilhado.
        if (ViagemHelper.outroDestino(mensagem) && sessao.dados.containsKey(DESTINO)
                && sessaoService.historicoNaoExtraido(sessao).stream().filter(item -> "usuario".equals(item.papel())).allMatch(item -> ViagemHelper.outroDestino(item.conteudo()))) {
            return sugerirOutroDestino(id, mensagem, sessao, tokens);
        }

        // Prioriza respostas locais seguras antes de qualquer chamada à LLM.
        boolean possuiContexto = !sessao.dados.isEmpty() || sessao.campoPendente != null || !sessao.candidatosPendentes.isEmpty() || !sessao.destinosSugeridos.isEmpty();

        if (!ViagemHelper.novoRoteiro(mensagem)) {
            String respostaConhecida = baseConhecimento.buscarRespostaLocal(mensagem, possuiContexto);
            if (respostaConhecida != null) {
                log.info("Base de conhecimento - Resposta local sem consulta à LLM");
                return montarRespostaBaseConhecimento(id, respostaConhecida, sessao, tokens);
            }
        }

        List<MensagemConversa> historico = sessaoService.historicoNaoExtraido(sessao);
        Map<String, Object> dadosExtraidos;
        if (DESTINO.equals(sessao.campoPendente) && ViagemHelper.delegacaoIsolada(mensagem)
                && historico.stream().noneMatch(item -> "usuario".equals(item.papel()))) {
            log.info("Destino delegado pelo usuário - Sem consulta ao extrator");
            dadosExtraidos = Map.of(DESTINO_DELEGADO, true);
        } else {
            dadosExtraidos = extratorDadosViagem.extrairDados(mensagem, sessao.campoPendente, Map.copyOf(sessao.dados), historico,
                    sessao.destinoDelegado, sessao.candidatosPendentes.isEmpty() && sessao.destinosSugeridos.isEmpty(),
                    (sistema, usuario) -> {
                        log.info("Extrator de dados: {}", sessao.configuracaoIa.extrator());
                        return llmService.consultarLlm(sessao.configuracaoIa.extrator(), sistema, usuario, tokens);
                    });
        }

        boolean dadosAlterados = sessaoService.atualizarDadosSessao(sessao, dadosExtraidos);
        sessao.indiceUltimaExtracao = sessao.historico.size() - 1;

        boolean rejeicaoOferta = !sessao.candidatosPendentes.isEmpty() && ViagemHelper.rejeicao(mensagem);
        boolean novoRoteiro = ViagemHelper.novoRoteiro(mensagem) || rejeicaoOferta;
        boolean comandoIsolado = novoRoteiro && !dadosAlterados && (rejeicaoOferta || ViagemHelper.novoRoteiroIsolado(mensagem));
        boolean contextoJaAprovado = comandoIsolado && sessao.dados.equals(sessao.dadosValidados);
        if (novoRoteiro) {
            if (comandoIsolado) sessaoService.registrarRejeicaoNoContexto(sessao);
            cacheRoteiroService.rejeitarCandidatos(sessao);
            sessao.ignorarCache = true;
        }

        if (!comandoIsolado) sessao.dadosValidados = null;
        if (dadosAlterados) {
            sessaoService.descartarOfertasDoContexto(sessao);
            sessao.candidatosPendentes = List.of();
            sessao.destinosSugeridos = List.of();
        }

        // Valida antes de gerar um roteiro ou tratar uma oferta já apresentada.
        // Durante a coleta, uma busca sem candidatos não precisa acionar o Judge.
        if (!contextoJaAprovado && possuiTrajeto(sessao)
                && (buscarCampoObrigatorioPendente(sessao).isEmpty() || !sessao.candidatosPendentes.isEmpty())) {
            Optional<ChatResponse> divergencia = validarContexto(id, sessao, tokens);
            if (divergencia.isPresent()) return divergencia.get();
        }

        Optional<ChatResponse> respostaPendente = cacheRoteiroService.processarCandidatosPendentes(id, mensagem, sessao, dadosAlterados, tokens);
        if (respostaPendente.isPresent()) {
            return respostaPendente.get();
        }

        // Preserva a busca antecipada, mas valida o contexto antes de exibir candidatos.
        if (cacheRoteiroService.oferecerCandidatos(mensagem, sessao)) {
            if (!sessao.dados.equals(sessao.dadosValidados)) {
                Optional<ChatResponse> divergencia = validarContexto(id, sessao, tokens);
                if (divergencia.isPresent()) {
                    sessao.candidatosPendentes = List.of();
                    return divergencia.get();
                }
            }
            return montarRespostaLocal(id, cacheRoteiroService.montarOfertaCandidatos(sessao), sessao, tokens);
        }

        Optional<String> campoFaltante = buscarCampoColetaPendente(sessao);
        if (campoFaltante.isPresent()) {
            return solicitarCampoPendente(id, sessao, campoFaltante.get(), tokens);
        }

        return gerarPlanejamento(id, comandoIsolado ? "Gere um novo roteiro com os requisitos da viagem já informados, sem reutilizar o cache." : mensagem, sessao, tokens);
    }

    // Exclui a sugestão apenas desta sessão e aproveita os dados já coletados.
    private ChatResponse sugerirOutroDestino(String id, String mensagem, Sessao sessao, ContadorTokens tokens) {
        String anterior = sessao.dados.remove(DESTINO).toString();
        sessao.destinosRejeitados.add(ViagemHelper.normalizarDestino(anterior));
        sessao.destinosSugeridos = List.of();
        cacheRoteiroService.rejeitarCandidatos(sessao);
        sessao.destinoDelegado = true;
        sessao.destinoRecomendado = false;
        sessao.destinoPerguntado = true;
        sessao.dadosValidados = null;
        sessao.ignorarCache = false;
        sessao.campoPendente = null;
        sessao.indiceUltimaExtracao = sessao.historico.size() - 1;
        log.info("Destino descartado na sessão: {} - Cache compartilhado preservado", anterior);

        Optional<String> campoFaltante = buscarCampoColetaPendente(sessao);
        if (campoFaltante.isPresent()) return solicitarCampoPendente(id, sessao, campoFaltante.get(), tokens);
        return gerarPlanejamento(id, mensagem, sessao, tokens);
    }

    // Solicita novas recomendações ao motor de inferência.
    private ChatResponse apresentarDestinosSugeridos(String id, Sessao sessao, ContadorTokens tokens) {
        sessao.destinosSugeridos = List.of();
        sessao.campoPendente = null;
        sessao.destinoPerguntado = true;
        try {
            sessao.destinosSugeridos = destinoService.recomendarDestinos(sessao, sessaoService.montarContextoGeracao(sessao, ""), tokens);
        } catch (IllegalStateException e) {
            log.warn("Não foi possível recomendar destinos: {}", e.getMessage());
            sessao.omitirRespostaDoContexto = true;
            return new ChatResponse(id, "Não consegui gerar as sugestões. Digite 'outra sugestão' para tentar novamente.",
                    "NAO_APLICAVEL", "LLM", Map.copyOf(sessao.dados), null, null, null, tokens.obterConsumo(), null);
        }
        return new ChatResponse(id, destinoService.montarMensagemDestinos(sessao), "NAO_APLICAVEL", "LLM", Map.copyOf(sessao.dados),
                null, null, null, tokens.obterConsumo(), null);
    }

    // Processa a escolha ou rejeição dos destinos sugeridos.
    private Optional<ChatResponse> processarDestinosPendentes(String id, String mensagem, Sessao sessao, ContadorTokens tokens) {
        if (sessao.destinosSugeridos.isEmpty()) {
            if (!sessao.dados.containsKey(DESTINO) && buscarCampoObrigatorioPendente(sessao).isEmpty()
                    && ViagemHelper.outroDestino(mensagem) && sessaoService.historicoNaoExtraido(sessao).stream().noneMatch(item -> "usuario".equals(item.papel()))) {
                sessao.indiceUltimaExtracao = sessao.historico.size() - 1;
                return Optional.of(apresentarDestinosSugeridos(id, sessao, tokens));
            }
            return Optional.empty();
        }
        if (sessaoService.historicoNaoExtraido(sessao).stream().anyMatch(item -> "usuario".equals(item.papel()))) return Optional.empty();

        if (ViagemHelper.outroDestino(mensagem) || ViagemHelper.rejeicao(mensagem)) {
            sessao.destinosSugeridos.forEach(destino ->
                    sessao.destinosRejeitados.add(ViagemHelper.normalizarDestino(destino.cidade())));

            log.info("Destinos descartados na sessão: {}", sessao.destinosRejeitados);

            sessao.indiceUltimaExtracao = sessao.historico.size() - 1;
            sessao.destinosSugeridos = List.of();

            return Optional.of(apresentarDestinosSugeridos(id, sessao, tokens));
        }

        int indice = ViagemHelper.identificarOpcaoSelecionada(mensagem, sessao.destinosSugeridos.size());

        if (indice >= 0) {
            var destino = sessao.destinosSugeridos.get(indice);

            return Optional.of(selecionarDestino(id, mensagem, sessao, destino, tokens));
        }

        return Optional.empty();
    }

    // Confirma o destino antes de consultar o cache ou gerar o roteiro.
    private ChatResponse selecionarDestino(String id, String mensagem, Sessao sessao, DestinoSugerido destino, ContadorTokens tokens) {
        sessao.dados.put(DESTINO, destino.cidade());
        sessao.destinoDelegado = false;
        sessao.destinoRecomendado = false;
        sessao.destinoPerguntado = true;
        sessao.destinosSugeridos = List.of();
        sessao.dadosValidados = null;
        sessao.campoPendente = null;
        sessao.indiceUltimaExtracao = sessao.historico.size() - 1;
        // Preserva a mensagem original e explicita a escolha para Judge e cache.
        sessao.selecoesInterpretadas.put(sessao.indiceUltimaExtracao,
                new MensagemConversa("usuario", "Escolho " + destino.cidade() + " - " + destino.pais() + "."));

        log.info("Destino selecionado pelo usuário: {}", destino.cidade());

        Optional<ChatResponse> divergencia = validarContexto(id, sessao, tokens);
        if (divergencia.isPresent()) return divergencia.get();

        if (cacheRoteiroService.oferecerCandidatos(textoSolicitacaoViagem(sessao), sessao)) {
            return montarRespostaLocal(id, cacheRoteiroService.montarOfertaCandidatos(sessao), sessao, tokens);
        }

        return gerarPlanejamento(id, "Gere o roteiro para o destino selecionado: " + destino.cidade() + " - " + destino.pais(), sessao, tokens);
    }

    private ChatResponse montarRespostaBaseConhecimento(String id, String respostaConhecida, Sessao sessao, ContadorTokens tokens) {
        return new ChatResponse(id, respostaConhecida, "NAO_APLICAVEL", "BASE_CONHECIMENTO", Map.copyOf(sessao.dados),
                null, null, null, tokens.obterConsumo(), null);
    }

    // O destino não é obrigatório para iniciar a inferência.
    private Optional<String> buscarCampoObrigatorioPendente(Sessao sessao) {
        return CAMPOS_OBRIGATORIOS.stream().filter(campo -> !sessao.dados.containsKey(campo)).findFirst();
    }

    // Pergunta o destino primeiro, mas não repete a pergunta se ele permanecer indefinido.
    private Optional<String> buscarCampoColetaPendente(Sessao sessao) {
        if (buscarCampoObrigatorioPendente(sessao).isEmpty()) return Optional.empty();
        return CAMPOS_COLETA.stream()
                .filter(campo -> !DESTINO.equals(campo) || (!sessao.destinoPerguntado && !sessao.destinoDelegado))
                .filter(campo -> !sessao.dados.containsKey(campo)).findFirst();
    }

    private ChatResponse solicitarCampoPendente(String id, Sessao sessao, String campo, ContadorTokens tokens) {
        sessao.campoPendente = campo;
        if (DESTINO.equals(campo)) sessao.destinoPerguntado = true;
        return montarRespostaLocal(id, baseConhecimento.redenrizarTemplate("coleta_" + campo, Map.of()), sessao, tokens);
    }

    // Gera o roteiro somente quando o destino estiver definido.
    private ChatResponse gerarPlanejamento(String id, String mensagem, Sessao sessao, ContadorTokens tokens) {
        String destino = sessao.dados.getOrDefault(DESTINO, "").toString();
        if (destino.isBlank()) {
            if (!sessao.destinosSugeridos.isEmpty()) return montarRespostaLocal(id, destinoService.montarMensagemDestinos(sessao), sessao, tokens);
            return apresentarDestinosSugeridos(id, sessao, tokens);
        }

        BigDecimal total = new BigDecimal(sessao.dados.get(ORCAMENTO).toString());
        String assinatura = cacheRoteiroService.gerarAssinatura(sessao.dados);
        ResultadoPlanejamento resultado = planejamentoService.criarPlano(id, mensagem, sessao, destino, total, assinatura, tokens);
        if (!resultado.avaliacao().aprovado()) {
            sessao.omitirRespostaDoContexto = true;
            return montarRespostaReprovacao(id, sessao, resultado.avaliacao(), tokens);
        }
        // Persiste após aprovação do Judge, independentemente da aceitação posterior do usuário.
        cacheRoteiroService.armazenarPlanejamento(textoSolicitacaoViagem(sessao), assinatura, resultado.plano());
        sessao.ignorarCache = false;
        return montarRespostaPlanejamento(id, sessao, resultado.plano(), tokens);
    }

    // Expõe a avaliação final sem entregar ou armazenar um roteiro reprovado.
    private ChatResponse montarRespostaReprovacao(String id, Sessao sessao, AvaliadorRoteiroService.ResultadoAvaliacao avaliacao, ContadorTokens tokens) {
        String titulo = avaliacao.status() == AvaliadorRoteiroService.Status.REPROVADO
                ? "Roteiro reprovado" : "Avaliação indisponível";
        String nota = avaliacao.nota() == null ? "\n\n**Judge:** nota não atribuída." : "";
        String motivos = avaliacao.motivos().isEmpty() ? "" : "\n\n**Motivos:**\n- " + String.join("\n- ", avaliacao.motivos());
        String resposta = "**" + titulo + "**" + nota + motivos + "\n\nVocê pode solicitar uma nova tentativa.";
        return new ChatResponse(id, resposta, "MISS", "LLM", Map.copyOf(sessao.dados), null, null, null, tokens.obterConsumo(), avaliacao.nota());
    }

    // Indexa a intenção da viagem, não o comando de rejeição ou confirmação do cache.
    private String textoSolicitacaoViagem(Sessao sessao) {
        return String.join("\n", sessaoService.historicoIntencao(sessao).stream()
                .filter(mensagem -> "usuario".equals(mensagem.papel())).map(MensagemConversa::conteudo).toList());
    }

    private ChatResponse montarRespostaPlanejamento(String id, Sessao sessao, PlanoArmazenado plano, ContadorTokens tokens) {
        return new ChatResponse(id, plano.resposta(), "MISS", "LLM", Map.copyOf(sessao.dados),
                plano.roteiro(), plano.orcamentoSugerido(), plano.fontes(), tokens.obterConsumo(), plano.notaAvaliacao());
    }

    // Resposta local de coleta ou confirmação, sem mudar o contrato do DTO.
    private ChatResponse montarRespostaLocal(String id, String resposta, Sessao sessao, ContadorTokens tokens) {
        return new ChatResponse(id, resposta, "NAO_APLICAVEL", "COLETA_DADOS", Map.copyOf(sessao.dados), null, null, null, tokens.obterConsumo(), null);
    }

    // Bloqueia cache e geração quando a interpretação diverge da conversa.
    private Optional<ChatResponse> validarContexto(String id, Sessao sessao, ContadorTokens tokens) {
        log.info("Judge - Validação contextual: {}", sessao.configuracaoIa.judge());
        sessao.dadosValidados = null;
        var validacao = avaliadorRoteiro.validarContexto(sessaoService.historicoIntencao(sessao), sessaoService.montarDadosAvaliacao(sessao, sessao.dados),
                (sistema, usuario) -> llmService.consultarLlm(sessao.configuracaoIa.judge(), sistema, usuario, tokens));
        if (validacao.aprovado()) {
            sessao.dadosValidados = Map.copyOf(sessao.dados);
            return Optional.empty();
        }

        // Motivos produzidos pelo Judge são diagnóstico, não requisitos do usuário.
        sessao.omitirRespostaDoContexto = true;
        if (validacao.status() == AvaliadorRoteiroService.Status.REPROVADO) {
            sessaoService.descartarOfertasDoContexto(sessao);
            sessao.candidatosPendentes = List.of();
        }
        String resposta = validacao.status() == AvaliadorRoteiroService.Status.INDISPONIVEL
                ? "Não consegui validar os dados da viagem. Tente novamente."
                : "Preciso confirmar os dados da viagem antes de continuar. " + String.join("; ", validacao.motivos()) + " Confirme ou corrija as informações.";
        return Optional.of(montarRespostaLocal(id, resposta, sessao, tokens));
    }

    private boolean possuiTrajeto(Sessao sessao) {
        return sessao.dados.get(ORIGEM) != null && sessao.dados.get(DESTINO) != null;
    }

}
