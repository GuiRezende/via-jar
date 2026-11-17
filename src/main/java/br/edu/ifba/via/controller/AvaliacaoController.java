package br.edu.ifba.via.controller;

import br.edu.ifba.via.model.RegistroAvaliacao;
import br.edu.ifba.via.service.AvaliadorRoteiroService.Status;
import br.edu.ifba.via.service.HistoricoAvaliacaoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/avaliacoes")
@RequiredArgsConstructor
public class AvaliacaoController {

    private final HistoricoAvaliacaoService historicoAvaliacaoService;

    @GetMapping
    public List<RegistroAvaliacao> listar(@RequestParam(name = "status", required = false) Status status) {
        return historicoAvaliacaoService.listar(status);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RegistroAvaliacao> buscarPorId(@PathVariable("id") UUID id) {
        return ResponseEntity.of(historicoAvaliacaoService.buscarPorId(id));
    }

    @GetMapping("/processamentos/{id}")
    public List<RegistroAvaliacao> buscarPorProcessamento(@PathVariable("id") UUID id) {
        return historicoAvaliacaoService.buscarPorProcessamento(id);
    }
}