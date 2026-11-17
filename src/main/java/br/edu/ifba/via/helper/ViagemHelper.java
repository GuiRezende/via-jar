package br.edu.ifba.via.helper;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Centraliza campos da viagem e padrões de comandos da conversa.
public final class ViagemHelper {
    public static final String ORIGEM = "origem";
    public static final String DESTINO = "destino";
    public static final String PERIODO = "periodo";
    public static final String DURACAO = "duracao";
    public static final String VIAJANTES = "viajantes";
    public static final String ORCAMENTO = "orcamento";
    public static final String MOEDA = "moeda";
    public static final String PREFERENCIAS = "preferencias";
    public static final String RESTRICOES = "restricoes";
    public static final String DESTINO_DELEGADO = "destinoDelegado";
    public static final List<String> CAMPOS_COLETA = List.of(DESTINO, ORIGEM, PERIODO, DURACAO, VIAJANTES, ORCAMENTO, MOEDA);
    public static final List<String> CAMPOS_OBRIGATORIOS = List.of(ORIGEM, PERIODO, DURACAO, VIAJANTES, ORCAMENTO, MOEDA);
    public static final String DESTINOS_REJEITADOS = "destinosRejeitados";
    public static final List<String> CAMPOS_VIAGEM = List.of(ORIGEM, DESTINO, PERIODO, DURACAO, VIAJANTES, ORCAMENTO, MOEDA, PREFERENCIAS, RESTRICOES);
    private static final Pattern PADRAO_NOVO_ROTEIRO = Pattern.compile("(?iu)^(?:novo|(?:criar|crie|quero criar|quero)\\s+(?:um\\s+)?novo|prefiro\\s+(?:um\\s+)?novo)\\b.*");
    private static final Pattern PADRAO_REJEICAO = Pattern.compile("(?iu)^(?:não|nao)(?:[,.! ]*|\\s*[, ]?\\s*(?:obrigad[oa]|(?:quero\\s+)?(?:reutilizar|usar|aproveitar)(?:\\s+(?:esse|este|o)\\s+roteiro)?)[.! ]*)$");
    private static final Pattern PADRAO_NOVO_ROTEIRO_ISOLADO = Pattern.compile("(?iu)^(?:novo(?:\\s+roteiro)?|(?:criar|crie|quero criar|quero|prefiro)\\s+(?:um\\s+)?novo(?:\\s+roteiro)?)[.! ]*$");
    private static final Pattern PADRAO_PERSONALIZACAO = Pattern.compile("(?i).*\\b(modificar|personalizar|alterar)\\b.*");
    private static final Pattern PADRAO_SELECAO = Pattern.compile("(?i)^(?:sim[, ]*)?(?:op[cç][ãa]o\\s*)?([1-3])$");
    private static final Pattern PADRAO_CONFIRMACAO = Pattern.compile("(?i)^(sim|aceito|reutilizar|usar|aproveitar|quero reutilizar|pode reutilizar)\\b.*");

    private static final Set<String> COMANDOS_DELEGACAO = Set.of("quero sua sugestao", "prefiro receber sugestoes", "quero receber sugestoes", "quero uma sugestao", "pode sugerir", "pode escolher", "escolha por mim");

    private static final Set<String> COMANDOS_OUTRO_DESTINO = Set.of("me sugere outro destino", "me sugira outro destino", "sugira outro destino", "sugere outro destino", "quero outro destino", "prefiro outro destino", "pode sugerir outro destino", "recomende outro destino", "outro destino", "me recomenda outro destino");

    private static final Pattern PADRAO_OUTRO_DESTINO = Pattern.compile(
            "(?:(?:me )?(?:sugere|sugira|recomende|recomenda|quero|prefiro|pode sugerir) )?"
                    + "(?:um |uma )?(?:outro[s]? destino[s]?|outra[s]? cidade[s]?|outra[s]? sugest(?:ao|oes)"
                    + "|outra[s]? recomendac(?:ao|oes)|outro[s]? lugar(?:es)?|outra[s]? opc(?:ao|oes))"
    );

    private static final Pattern PADRAO_DURACAO = Pattern.compile("([0-9]{1,9})(?:\\s*(dia[s]?|semana[s]?))?");
    private static final Pattern PADRAO_VIAJANTES = Pattern.compile("([0-9]{1,9})(?:\\s*(?:pessoa[s]?|viajante[s]?))?");
    private static final String EXPRESSAO_MOEDA = "(?:r\\$|us\\$|€|brl|usd|eur|real|reais|dolar|dolares|euro|euros)";
    private static final String EXPRESSAO_VALOR = "(?:[0-9]{1,3}(?:\\.[0-9]{3})+|[0-9]{1,12})(?:,[0-9]{1,2})?";
    private static final Pattern PADRAO_ORCAMENTO = Pattern.compile("(?:(" + EXPRESSAO_MOEDA + ")\\s*)?(" + EXPRESSAO_VALOR + ")(?:\\s*(k|mil))?(?:\\s*(" + EXPRESSAO_MOEDA + "))?");
    private static final Pattern PADRAO_MOEDA = Pattern.compile("(?:(?:sim|isso|correto)[, ]+)?(?:em\\s+)?(" + EXPRESSAO_MOEDA + ")");

    private static final Locale LOCALE_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter FORMATO_MES_ANO = DateTimeFormatter.ofPattern("MMMM 'de' uuuu", LOCALE_BR);
    private static final Pattern PADRAO_PERIODO = Pattern.compile("(\\d{2}/\\d{2}/\\d{4})\\s*(?:ate|a|-)\\s*(\\d{2}/\\d{2}/\\d{4})");
    private static final Pattern PADRAO_MES_ANO = Pattern.compile("(janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)\\s+(?:de\\s+)?(\\d{4})");
    private static final List<String> MESES = List.of("janeiro", "fevereiro", "marco", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro");

    private ViagemHelper() {
    }

    // Reconhece somente comandos isolados, sem descartar requisitos adicionais.
    public static boolean outroDestino(String mensagem) {
        if (mensagem == null || mensagem.isBlank()) return false;
        String texto = TextoHelper.normalizarPergunta(mensagem.replace("\"", "").replace("'", ""));
        return PADRAO_OUTRO_DESTINO.matcher(texto).matches();
    }

    public static String normalizarDestino(String destino) {
        return TextoHelper.normalizarPergunta(destino);
    }

    // Só deve ser usado quando o sistema está solicitando o destino.
    public static boolean delegacaoIsolada(String mensagem) {
        return COMANDOS_DELEGACAO.contains(TextoHelper.normalizarPergunta(mensagem));
    }

    public static boolean novoRoteiro(String mensagem) {
        return PADRAO_NOVO_ROTEIRO.matcher(mensagem.strip()).matches();
    }

    public static boolean novoRoteiroIsolado(String mensagem) {
        return PADRAO_NOVO_ROTEIRO_ISOLADO.matcher(mensagem.strip()).matches();
    }

    public static boolean rejeicao(String mensagem) {
        return PADRAO_REJEICAO.matcher(mensagem.strip()).matches();
    }

    public static boolean personalizacao(String mensagem) {
        return PADRAO_PERSONALIZACAO.matcher(mensagem.strip()).matches();
    }

    public static int identificarOpcaoSelecionada(String mensagem, int quantidadeOpcoes) {
        Matcher correspondencia = PADRAO_SELECAO.matcher(mensagem.strip());
        if (correspondencia.matches()) {
            int indice = Integer.parseInt(correspondencia.group(1)) - 1;
            return indice < quantidadeOpcoes ? indice : -1;
        }
        return quantidadeOpcoes == 1 && PADRAO_CONFIRMACAO.matcher(mensagem.strip()).matches() ? 0 : -1;
    }

    // Identifica períodos completos e calcula a duração quando possível.
    private static Map<String, Object> extrairPeriodo(String texto) {
        try {
            var intervalo = PADRAO_PERIODO.matcher(texto);

            if (intervalo.matches()) {
                LocalDate inicio = LocalDate.parse(intervalo.group(1), FORMATO_DATA);
                LocalDate fim = LocalDate.parse(intervalo.group(2), FORMATO_DATA);

                if (fim.isBefore(inicio)) return Map.of();

                long duracao = ChronoUnit.DAYS.between(inicio, fim) + 1;
                String periodo = inicio.format(FORMATO_DATA) + " até " + fim.format(FORMATO_DATA);

                return Map.of(PERIODO, periodo, DURACAO, Math.toIntExact(duracao));
            }

            var mesAno = PADRAO_MES_ANO.matcher(texto);

            if (mesAno.matches()) {
                Month mes = Month.of(MESES.indexOf(mesAno.group(1)) + 1);
                int ano = Integer.parseInt(mesAno.group(2));

                YearMonth periodo = YearMonth.of(ano, mes);

                return Map.of(PERIODO, periodo.format(FORMATO_MES_ANO));
            }

            return Map.of();
        } catch (DateTimeException | ArithmeticException e) {
            return Map.of();
        }
    }

    // Interpreta respostas completas e simples somente quando há um campo pendente.
    // Um mapa vazio indica que a mensagem precisa ser interpretada pela LLM.
    public static Map<String, Object> extrairDadosLocais(String mensagem, String campoPendente) {
        if (mensagem == null || campoPendente == null || mensagem.isBlank()) return Map.of();
        String texto = ORCAMENTO.equals(campoPendente) ? TextoHelper.normalizar(mensagem) : TextoHelper.normalizarPergunta(mensagem);
        return switch (campoPendente) {
            case PERIODO -> extrairPeriodo(texto);
            case DURACAO -> extrairDuracao(texto);
            case VIAJANTES -> extrairViajantes(texto);
            case ORCAMENTO -> extrairOrcamento(texto);
            case MOEDA -> extrairMoeda(texto);
            default -> Map.of();
        };
    }

    private static Map<String, Object> extrairDuracao(String texto) {
        var correspondencia = PADRAO_DURACAO.matcher(texto);
        if (!correspondencia.matches()) return Map.of();
        long dias = Long.parseLong(correspondencia.group(1));
        if (correspondencia.group(2) != null && correspondencia.group(2).startsWith("semana")) dias *= 7;
        return dias > 0 && dias <= Integer.MAX_VALUE ? Map.of(DURACAO, (int) dias) : Map.of();
    }

    private static Map<String, Object> extrairViajantes(String texto) {
        var correspondencia = PADRAO_VIAJANTES.matcher(texto);
        if (!correspondencia.matches()) return Map.of();
        int quantidade = Integer.parseInt(correspondencia.group(1));
        return quantidade > 0 ? Map.of(VIAJANTES, quantidade) : Map.of();
    }

    private static Map<String, Object> extrairOrcamento(String texto) {
        var correspondencia = PADRAO_ORCAMENTO.matcher(texto);
        if (!correspondencia.matches()) return Map.of();
        String moedaInicial = codigoMoeda(correspondencia.group(1));
        String moedaFinal = codigoMoeda(correspondencia.group(4));
        if (moedaInicial != null && moedaFinal != null && !moedaInicial.equals(moedaFinal)) return Map.of();
        String valorTexto = correspondencia.group(2).replace(".", "").replace(',', '.');
        BigDecimal valor = new BigDecimal(valorTexto);
        if (correspondencia.group(3) != null) valor = valor.multiply(BigDecimal.valueOf(1000));
        if (valor.signum() <= 0) return Map.of();
        Map<String, Object> dados = new HashMap<>();
        dados.put(ORCAMENTO, valor);
        String moeda = moedaInicial != null ? moedaInicial : moedaFinal;
        if (moeda != null) dados.put(MOEDA, moeda);
        return Map.copyOf(dados);
    }

    private static Map<String, Object> extrairMoeda(String texto) {
        var correspondencia = PADRAO_MOEDA.matcher(texto);
        return correspondencia.matches() ? Map.of(MOEDA, codigoMoeda(correspondencia.group(1))) : Map.of();
    }

    private static String codigoMoeda(String moeda) {
        if (moeda == null) return null;
        return switch (moeda) {
            case "r$", "brl", "real", "reais" -> "BRL";
            case "us$", "usd", "dolar", "dolares" -> "USD";
            case "€", "eur", "euro", "euros" -> "EUR";
            default -> null;
        };
    }
}
