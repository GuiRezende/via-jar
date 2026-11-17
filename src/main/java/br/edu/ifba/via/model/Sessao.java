package br.edu.ifba.via.service;

import br.edu.ifba.via.dto.ConfiguracaoIa;
import br.edu.ifba.via.dto.DestinoSugerido;
import br.edu.ifba.via.service.AvaliadorRoteiroService.MensagemConversa;

import java.time.Instant;
import java.util.*;

// Estado interno da conversa; acesso protegido pelo monitor da sessão.
final class Sessao {
    // Provedores utilizados na requisição atual, protegidos pelo lock da sessão.
    ConfiguracaoIa configuracaoIa;
    Map<String, Object> dados = new HashMap<>();
    Instant atualizadaEm = Instant.now();
    String campoPendente;
    boolean destinoDelegado;
    boolean destinoPerguntado;
    boolean destinoRecomendado;
    final Set<String> destinosRejeitados = new HashSet<>();
    int indiceUltimaExtracao = -1;
    final List<MensagemConversa> historico = new ArrayList<>();
    final Map<Integer, MensagemConversa> selecoesInterpretadas = new HashMap<>();
    final Set<Integer> indicesForaDoContexto = new HashSet<>();
    Map<String, Object> dadosValidados;
    final Set<Integer> indicesOfertasPendentes = new HashSet<>();
    boolean omitirRespostaDoContexto;
    boolean ignorarCache;
    List<CacheSemantico.Candidate> candidatosPendentes = List.of();
    Set<String> assinaturasRejeitadas = new HashSet<>();
    List<DestinoSugerido> destinosSugeridos = List.of();
}
