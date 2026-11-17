package br.edu.ifba.via.helper;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

// Centraliza padrões, normalização e verificações textuais da base de conhecimento.
public final class TextoHelper {

    private static final Pattern PADRAO_MENSAGEM_COMPOSTA = Pattern.compile(".*[,.!?;].*\\s+.*");
    private static final Pattern PADRAO_RESTRICAO = Pattern.compile(".*\\b(nao|nunca|sem|mas|porem|exceto)\\b.*");
    private static final Pattern PADRAO_PLANEJAMENTO = Pattern.compile(".*\\b(para|saindo|viajar|viagem em|reais|brl|dias|pessoas|orcamento de|tenho [0-9])\\b.*");
    private static final Pattern PADRAO_NUMERICO = Pattern.compile(".*\\d.*");
    private static final Pattern PADRAO_MARCADOR_TEMPLATE = Pattern.compile("(?s).*\\{[a-zA-Z][a-zA-Z]*}.*");
    private static final Pattern PADRAO_ACENTUACAO = Pattern.compile("\\p{M}");
    private static final Pattern PADRAO_ESPACOS = Pattern.compile("\\s+");
    private static final Pattern PADRAO_PONTUACAO_FINAL = Pattern.compile("[.!?]+$");
    private static final Pattern PADRAO_BLOCO_JSON = Pattern.compile("(?is)^```(?:json)?\\s*(.*?)\\s*```$");

    // Mensagens sociais isoladas podem usar similaridade sem risco de descartar dados de viagem.
    private static final Set<String> MENSAGENS_SOCIAIS = Set.of(
            "oi", "ola", "opa", "e ai", "bom dia", "boa tarde", "boa noite",
            "tudo bem", "oi tudo bem", "ola tudo bem", "obrigado", "obrigada",
            "muito obrigado", "muito obrigada", "valeu", "tchau", "ate logo", "ate mais");

    private TextoHelper() {
    }

    // Identifica uma mensagem social isolada, sem dados adicionais.
    public static boolean mensagemSocial(String mensagem) {
        return MENSAGENS_SOCIAIS.contains(normalizarPergunta(mensagem));
    }

    // Identifica marcadores de template ainda não preenchidos.
    public static boolean possuiMarcadorTemplate(String mensagem) {
        return PADRAO_MARCADOR_TEMPLATE.matcher(mensagem).matches();
    }

    public static String normalizarJson(String texto) {
        if (texto == null) return null;

        String conteudo = texto.strip();
        var correspondencia = PADRAO_BLOCO_JSON.matcher(conteudo);

        return correspondencia.matches() ? correspondencia.group(1).strip() : conteudo;
    }

    // Ignora apenas pontuação final, preservando o conteúdo integral da mensagem.
    public static String normalizarPergunta(String texto) {
        return PADRAO_PONTUACAO_FINAL.matcher(normalizar(texto)).replaceFirst("").strip();
    }

    /**
     * Identifica mensagens compostas por múltiplos trechos.
     */
    public static boolean mensagemComposta(String mensagem) {
        return PADRAO_MENSAGEM_COMPOSTA.matcher(mensagem).matches();
    }

    /**
     * Identifica negações, exceções e possíveis restrições.
     */
    public static boolean contemRestricoes(String mensagem) {
        return PADRAO_RESTRICAO.matcher(mensagem).matches();
    }

    /**
     * Identifica termos associados ao planejamento de viagens,
     * como orçamento, duração, origem e quantidade de viajantes.
     */
    public static boolean contemDadosPlanejamento(String mensagem) {
        return PADRAO_PLANEJAMENTO.matcher(mensagem).matches();
    }

    /**
     * Identifica números que podem representar datas,
     * orçamento, duração ou quantidade de viajantes.
     */
    public static boolean contemNumeros(String mensagem) {
        return PADRAO_NUMERICO
                .matcher(mensagem)
                .matches();
    }

    /**
     * Normaliza a mensagem para reduzir variações textuais
     * durante a aplicação das regras e a busca semântica.
     *
     * Remove acentos, espaços excedentes e diferenças
     * entre letras maiúsculas e minúsculas.
     */
    public static String normalizar(String texto) {
        String textoNormalizado = Normalizer.normalize(texto.toLowerCase(Locale.ROOT).strip(), Normalizer.Form.NFD);

        textoNormalizado = PADRAO_ACENTUACAO.matcher(textoNormalizado).replaceAll("");

        return PADRAO_ESPACOS.matcher(textoNormalizado).replaceAll(" ");
    }

}
