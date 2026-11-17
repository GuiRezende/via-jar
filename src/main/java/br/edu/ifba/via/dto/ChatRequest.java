package br.edu.ifba.via.dto;

// Representa a solicitação de interação com o especialista de viagens.
public record ChatRequest(
        String sessao,
        String mensagem,
        ConfiguracaoIa configuracaoIa
) {

    // Mantém a compatibilidade com requisições sem configuração explícita de IA.
    public ChatRequest(String sessao, String mensagem) {
        this(sessao, mensagem, null);
    }
}