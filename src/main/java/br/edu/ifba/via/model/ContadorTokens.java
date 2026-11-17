package br.edu.ifba.via.model;

import br.edu.ifba.via.dto.ConsumoTokens;
import org.springframework.ai.chat.metadata.Usage;

// Acumula tokens por requisição; dados incompletos tornam o consumo desconhecido.
public final class ContadorTokens {
    private final ContadorTokens acumuladorPai;
    private int entrada;
    private int saida;
    private int total;
    private boolean consumoMensurado = true;

    public ContadorTokens() {
        this(null);
    }

    private ContadorTokens(ContadorTokens acumuladorPai) {
        this.acumuladorPai = acumuladorPai;
    }

    // Mede uma etapa isolada sem perder o consumo total da requisição.
    public ContadorTokens criarContadorParcial() {
        return new ContadorTokens(this);
    }

    public void acumular(Usage consumo) {
        if (acumuladorPai != null) acumuladorPai.acumular(consumo);
        if (consumo == null || consumo.getPromptTokens() == null || consumo.getCompletionTokens() == null || consumo.getTotalTokens() == null) {
            consumoMensurado = false;
            return;
        }
        entrada += consumo.getPromptTokens();
        saida += consumo.getCompletionTokens();
        total += consumo.getTotalTokens();
    }

    public ConsumoTokens obterConsumo() {
        return consumoMensurado ? new ConsumoTokens(entrada, saida, total) : new ConsumoTokens(null, null, null);
    }
}
