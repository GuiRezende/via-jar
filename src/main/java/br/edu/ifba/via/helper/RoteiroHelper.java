package br.edu.ifba.via.helper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class RoteiroHelper {

    public static final String INSTRUCAO_FORMATO = """
            Retorne somente um objeto JSON válido, sem Markdown, com a estrutura:
            {"dias":[{"dia":1,"descricao":"Atividades planejadas para o primeiro dia."}]}
            Inclua exatamente a quantidade de dias solicitada, numerados de 1 até a duração, em ordem.
            Cada descrição deve conter atividades relevantes para aquele dia, sem títulos de dias, sem campos extras.
            Não inclua reticências como substituição de dias. Não inclua comentários fora do JSON.
            """;
    private static final String DIAS = "dias";
    private static final String DIA = "dia";
    private static final String DESCRICAO = "descricao";
    private static final Pattern QUEBRAS_LINHA = Pattern.compile("\\R+");
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    private RoteiroHelper() {}

    // Valida a estrutura e converte os dias para Markdown compatível com o frontend e o cache.
    public static String formatarRoteiro(String resposta, int duracao) {
        if (resposta == null || resposta.isBlank() || duracao <= 0) throw new IllegalArgumentException("Roteiro vazio ou duração inválida.");

        try {
            var objeto = JSON.readTree(TextoHelper.normalizarJson(resposta));
            if (objeto == null || !objeto.isObject() || objeto.size() != 1 || !objeto.path(DIAS).isArray()) {
                throw new IllegalArgumentException("O roteiro deve conter somente o array dias.");
            }
            var dias = objeto.path(DIAS);
            if (dias.size() != duracao) throw new IllegalArgumentException("O roteiro deve conter exatamente " + duracao + " dias.");

            List<String> blocos = new ArrayList<>();
            int esperado = 1;
            for (var item : dias) {
                var numero = item.path(DIA);
                var descricao = item.path(DESCRICAO);
                if (!item.isObject() || item.size() != 2 || !numero.isIntegralNumber() || !numero.canConvertToInt() || numero.intValue() != esperado) {
                    throw new IllegalArgumentException("Dia inválido: esperado o número " + esperado + " em ordem, sem duplicidades.");
                }
                if (!descricao.isTextual() || descricao.asText().isBlank()) {
                    throw new IllegalArgumentException("Descrição obrigatória para o dia " + esperado + ".");
                }
                String conteudo = QUEBRAS_LINHA.matcher(descricao.asText().strip()).replaceAll(" ");
                blocos.add("## Dia " + esperado + "\n\n" + conteudo);
                esperado++;
            }
            return String.join("\n\n", blocos);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("A LLM retornou um JSON de roteiro inválido ou incompleto.", e);
        }
    }
}
