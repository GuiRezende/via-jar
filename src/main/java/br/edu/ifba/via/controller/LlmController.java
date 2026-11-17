package br.edu.ifba.via.controller;

import br.edu.ifba.via.enums.ProvedorLlm;
import br.edu.ifba.via.service.SeletorLlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

// Disponibiliza operações relacionadas aos provedores de IA.
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    private static final Logger log = LoggerFactory.getLogger(LlmController.class);

    private final SeletorLlmService seletor;

    public LlmController(SeletorLlmService seletor) {
        this.seletor = seletor;
    }

    // Verifica a disponibilidade do provedor realizando uma inferência real.
    @GetMapping("/status/{provedor}")
    public ResponseEntity<Map<String, Object>> verificarDisponibilidade(@PathVariable ProvedorLlm provedor) {
        try {
            ChatClient cliente = seletor.selecionar(provedor);

            String resposta = cliente.prompt().user("Responda apenas OK").call().content();

            return ResponseEntity.ok(Map.of(
                    "provedor", provedor,
                    "disponivel", resposta != null && !resposta.isBlank(),
                    "resposta", resposta == null ? "" : resposta));

        } catch (Exception e) {
            log.error("Erro ao consultar o provedor {}", provedor, e);

            return ResponseEntity.status(503).body(Map.of(
                    "provedor", provedor,
                    "disponivel", false,
                    "mensagem", "Provedor de IA indisponível"));
        }
    }
}