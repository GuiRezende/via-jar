package br.edu.ifba.via.dto;

import java.util.Map;
import java.util.List;
import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatResponse(
        String sessao,
        String resposta,
        String cache,
        String origemResposta,
        Map<String, Object> dados,
        String roteiro,
        Map<String, BigDecimal> orcamentoSugerido,
        List<Fonte> fontes,
        ConsumoTokens consumoTokens,
        Double notaAvaliacao) {

    public ChatResponse(String sessao, String resposta, String cache, String origemResposta, Map<String, Object> dados) {
        this(sessao, resposta, cache, origemResposta, dados, null, null, null, new ConsumoTokens(0, 0, 0), null);
    }

    public ChatResponse(String sessao, String resposta, String cache, String origemResposta, Map<String, Object> dados,
                        String roteiro, Map<String, BigDecimal> orcamentoSugerido, List<Fonte> fontes, Double notaAvaliacao) {
        this(sessao, resposta, cache, origemResposta, dados, roteiro, orcamentoSugerido, fontes, new ConsumoTokens(0, 0, 0), notaAvaliacao);
    }
}
