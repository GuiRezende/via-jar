package br.edu.ifba.via.dto;

import java.math.BigDecimal;

// Representa os dados de planejamento identificados pela LLM
public record DadosViagemExtraidos(
        String origem,
        String destino,
        String periodo,
        Integer duracao,
        Integer viajantes,
        BigDecimal orcamento,
        String moeda,
        String preferencias,
        String restricoes
) {
}