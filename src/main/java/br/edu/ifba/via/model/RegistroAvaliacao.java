package br.edu.ifba.via.model;

import br.edu.ifba.via.dto.ConfiguracaoIa;
import br.edu.ifba.via.dto.ConsumoTokens;
import br.edu.ifba.via.dto.Fonte;
import br.edu.ifba.via.service.AvaliadorRoteiroService.MensagemConversa;
import br.edu.ifba.via.service.AvaliadorRoteiroService.ResultadoAvaliacao;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

// Representa uma tentativa de avaliação de roteiro, independente do cache semântico.
public record RegistroAvaliacao(
        UUID id,
        UUID idProcessamento,
        String idSessao,
        Instant dataHora,
        OrigemAvaliacao origem,
        int tentativa,
        ConfiguracaoIa configuracaoIa,
        List<MensagemConversa> historico,
        Map<String, Object> dadosViagem,
        String roteiro,
        String respostaBrutaGerador,
        Map<String, BigDecimal> orcamentoSugerido,
        List<Fonte> fontes,
        ResultadoAvaliacao avaliacao,
        List<String> respostasBrutasJudge,
        ConsumoTokens consumoTokens,
        Long tempoProcessamentoMs,
        String assinaturaCache
) {
    public RegistroAvaliacao {
        Objects.requireNonNull(id, "Identificador da avaliação obrigatório");
        Objects.requireNonNull(idProcessamento, "Identificador do processamento obrigatório");
        Objects.requireNonNull(dataHora, "Data da avaliação obrigatória");
        Objects.requireNonNull(origem, "Origem da avaliação obrigatória");
        Objects.requireNonNull(configuracaoIa, "Configuração de IA obrigatória");
        Objects.requireNonNull(avaliacao, "Resultado da avaliação obrigatório");
        if (idSessao == null || idSessao.isBlank()) throw new IllegalArgumentException("Sessão obrigatória");
        if (tentativa < 1) throw new IllegalArgumentException("Tentativa inválida");
        if (tempoProcessamentoMs != null && tempoProcessamentoMs < 0) throw new IllegalArgumentException("Tempo inválido");

        // Preserva uma cópia dos dados no momento da avaliação.
        historico = List.copyOf(historico);
        dadosViagem = Map.copyOf(dadosViagem);
        orcamentoSugerido = Map.copyOf(orcamentoSugerido);
        fontes = List.copyOf(fontes);
        respostasBrutasJudge = List.copyOf(respostasBrutasJudge);
    }

    public enum OrigemAvaliacao { GERACAO, CACHE }
}
