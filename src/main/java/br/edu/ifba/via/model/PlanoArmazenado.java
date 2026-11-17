package br.edu.ifba.via.model;

import br.edu.ifba.via.dto.Fonte;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

// Contrato JSON persistido no cache; preserva os nomes dos campos.
public record PlanoArmazenado(String formato, String destino, String resposta, String roteiro,
                              Map<String, BigDecimal> orcamentoSugerido, List<Fonte> fontes, Double notaAvaliacao) {
    public static final String VERSAO_FORMATO_PLANO = "via-plano-v4";
}
