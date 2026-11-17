package br.edu.ifba.via.controller;

import br.edu.ifba.via.dto.ChatRequest;
import br.edu.ifba.via.dto.ChatResponse;
import br.edu.ifba.via.service.ChatService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService service;

    // Injeta o serviço responsável pela orquestração do planejamento de viagens.
    public ChatController(ChatService service) {
        this.service = service;
    }

    // Recebe a mensagem do usuário e delega seu processamento ao ChatService.
    // Retorna a resposta do especialista, incluindo os dados do planejamento,
    // informações do cache semântico, consumo de tokens e nota do Judge.
    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return service.responderMensagem(request);
    }

    // Intercepta exceções relacionadas a argumentos inválidos durante o processamento.
    // Retorna uma resposta HTTP 400 com a descrição do erro.
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String, String> invalid(IllegalArgumentException e) {
        return java.util.Map.of("erro", e.getMessage());
    }
}