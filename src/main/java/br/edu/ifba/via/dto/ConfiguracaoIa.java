package br.edu.ifba.via.dto;

import br.edu.ifba.via.enums.ProvedorLlm;

// Define os provedores de IA utilizados em cada etapa do processamento.
public record ConfiguracaoIa(
        ProvedorLlm extrator,
        ProvedorLlm inferencia,
        ProvedorLlm judge
) {
}