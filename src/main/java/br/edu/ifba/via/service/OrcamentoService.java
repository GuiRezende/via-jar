package br.edu.ifba.via.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

// Responsável pela distribuição do orçamento de viagem
@Service
public class OrcamentoService {

    private static final BigDecimal PERCENTUAL_TRANSPORTE = new BigDecimal("0.25");
    private static final BigDecimal PERCENTUAL_HOSPEDAGEM = new BigDecimal("0.35");
    private static final BigDecimal PERCENTUAL_ALIMENTACAO = new BigDecimal("0.20");
    private static final BigDecimal PERCENTUAL_PASSEIOS = new BigDecimal("0.10");

    private static final int CASAS_DECIMAIS = 2;
    private static final RoundingMode MODO_ARREDONDAMENTO = RoundingMode.HALF_UP;

    // Distribui o orçamento total entre as categorias de despesas, reservando o saldo restante para imprevistos.
    public Map<String, BigDecimal> alocarOrcamento(BigDecimal total) {
        validarOrcamento(total);

        Map<String, BigDecimal> distribuicao = distribuirOrcamento(total);

        distribuicao.put("imprevistos", calcularReservaImprevistos(total, distribuicao));

        return distribuicao;
    }

    // Distribui o orçamento conforme os percentuais definidos.
    private Map<String, BigDecimal> distribuirOrcamento(BigDecimal total) {
        Map<String, BigDecimal> distribuicao = new LinkedHashMap<>();

        distribuicao.put("transporte", calcularPercentual(total, PERCENTUAL_TRANSPORTE));
        distribuicao.put("hospedagem", calcularPercentual(total, PERCENTUAL_HOSPEDAGEM));
        distribuicao.put("alimentacao", calcularPercentual(total, PERCENTUAL_ALIMENTACAO));
        distribuicao.put("passeios", calcularPercentual(total, PERCENTUAL_PASSEIOS));

        return distribuicao;
    }

    //Calcula o valor proporcional ao percentual da categoria.
    private BigDecimal calcularPercentual(BigDecimal total, BigDecimal percentual) {
        return arredondar(total.multiply(percentual));
    }

    //Calcula a reserva para imprevistos utilizando o saldo restante,
    // compensando possíveis diferenças de arredondamento.
    private BigDecimal calcularReservaImprevistos(BigDecimal total, Map<String, BigDecimal> distribuicao) {
        BigDecimal valorUtilizado = distribuicao.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return arredondar(total).subtract(valorUtilizado);
    }

    //Padroniza o arredondamento dos valores monetários.
    private BigDecimal arredondar(BigDecimal valor) {
        return valor.setScale(CASAS_DECIMAIS, MODO_ARREDONDAMENTO);
    }

    //Garante que o orçamento informado seja positivo.
    private void validarOrcamento(BigDecimal total) {
        if (total == null || total.signum() <= 0) {
            throw new IllegalArgumentException("Orçamento deve ser positivo");
        }
    }
}