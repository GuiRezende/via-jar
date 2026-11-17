package br.edu.ifba.via.model;

import br.edu.ifba.via.service.AvaliadorRoteiroService;

public record ResultadoPlanejamento(PlanoArmazenado plano, AvaliadorRoteiroService.ResultadoAvaliacao avaliacao) {}
