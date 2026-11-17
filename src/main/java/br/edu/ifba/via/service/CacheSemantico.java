package br.edu.ifba.via.service;

import br.edu.ifba.via.repository.CacheSemanticoRepository;
import br.edu.ifba.via.repository.CacheSemanticoRepository.Entrada;
import br.edu.ifba.via.repository.CacheSemanticoRepository.Pontuacao;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class CacheSemantico {

    private static final int LIMITE_ENTRADAS = 100;
    private static final List<String> CAMPOS_COMPATIBILIDADE = List.of("origem", "destino", "periodo", "duracao", "viajantes", "orcamento", "moeda", "preferencias", "restricoes");

    private final LocalEmbeddings embeddings;
    private final CacheSemanticoRepository repository;
    private final double limiarSimilaridade;

    public CacheSemantico(LocalEmbeddings embeddings,
                          CacheSemanticoRepository repository,
                          @Value("${via.cache-threshold:0.75}") double limiarSimilaridade) {
        validarLimiar(limiarSimilaridade);
        this.embeddings = embeddings;
        this.repository = repository;
        this.limiarSimilaridade = limiarSimilaridade;
    }

    // Consulta somente registros do modelo e revisão atuais.
    private List<Entrada> listarEntradas() {
        return repository.listar(LocalEmbeddings.MODEL, embeddings.revision());
    }

    public synchronized String buscarRespostaSemelhante(String texto, String assinatura) {
        return buscarRespostaSemelhante(texto, assinatura, resposta -> true);
    }

    // Preserva a prioridade da primeira entrada compatível acima do limiar.
    public synchronized String buscarRespostaSemelhante(String texto, String assinatura, Predicate<String> respostaCompativel) {
        List<Entrada> compativeis = listarEntradas().stream()
                .filter(entrada -> entrada.assinatura().equals(assinatura))
                .filter(entrada -> respostaCompativel.test(entrada.resposta()))
                .toList();
        if (compativeis.isEmpty()) return null;

        Map<Long, Double> pontuacoes = ordenar(compativeis, texto).stream()
                .collect(Collectors.toMap(Pontuacao::id, Pontuacao::similaridade));

        return compativeis.stream()
                .filter(entrada -> pontuacoes.getOrDefault(entrada.id(), Double.NEGATIVE_INFINITY) >= limiarSimilaridade)
                .map(Entrada::resposta)
                .findFirst().orElse(null);
    }

    public synchronized String buscarRespostaPorAssinatura(String assinatura, Predicate<String> respostaCompativel) {
        return listarEntradas().reversed().stream()
                .filter(entrada -> entrada.assinatura().equals(assinatura))
                .map(Entrada::resposta)
                .filter(respostaCompativel)
                .findFirst().orElse(null);
    }

    public synchronized List<Candidate> buscarRoteirosPorTrajeto(String texto, String origem, String destino, Predicate<String> respostaValida, int limite) {
        return buscarRoteirosPorTrajeto(texto, origem, destino, Map.of(), respostaValida, limite);
    }

    // Filtra os requisitos antes de calcular a similaridade no PostgreSQL.
    public synchronized List<Candidate> buscarRoteirosPorTrajeto(String texto, String origem, String destino, Map<String, ?> dadosSolicitados, Predicate<String> respostaValida, int limite) {
        if (limite < 0) throw new IllegalArgumentException("Limite de candidatos inválido");
        if (limite == 0 || origem == null || origem.isBlank() || destino == null || destino.isBlank()) return List.of();

        List<Entrada> compativeis = listarEntradas().reversed().stream()
                .filter(entrada -> {
                    var dados = extrairDadosAssinatura(entrada.assinatura());
                    return mesmoTrajeto(origem, destino, dados)
                            && dadosCompativeis(dadosSolicitados, dados)
                            && respostaValida.test(entrada.resposta());
                }).toList();
        if (compativeis.isEmpty()) return List.of();

        Map<Long, Entrada> porId = compativeis.stream()
                .collect(Collectors.toMap(Entrada::id, entrada -> entrada));

        // O ranking por trajeto não aplica o limiar da busca por assinatura.
        return ordenar(compativeis, texto).stream()
                .limit(limite)
                .map(pontuacao -> {
                    Entrada entrada = porId.get(pontuacao.id());
                    return new Candidate(entrada.assinatura(), extrairDadosAssinatura(entrada.assinatura()),
                            entrada.resposta(), pontuacao.similaridade());
                }).toList();
    }

    private List<Pontuacao> ordenar(List<Entrada> entradas, String texto) {
        float[] vetor = validarVetorEmbedding(embeddings.embed(texto));
        return repository.ordenarPorSimilaridade(entradas.stream().map(Entrada::id).toList(), vetor);
    }

    public synchronized void armazenarResposta(String texto, String assinatura, String resposta) {
        float[] vetor = validarVetorEmbedding(embeddings.embed(texto));
        repository.armazenar(texto, assinatura, resposta, vetor, LocalEmbeddings.MODEL,
                embeddings.revision(), LIMITE_ENTRADAS);
    }

    private boolean dadosCompativeis(Map<String, ?> dadosSolicitados, Map<String, String> dadosSalvos) {
        for (String campo : CAMPOS_COMPATIBILIDADE) {
            Object valor = dadosSolicitados.get(campo);
            if (valor != null && !valor.toString().isBlank() && !valoresEquivalentes(valor, dadosSalvos.get(campo))) return false;
        }
        return true;
    }

    private static boolean valoresEquivalentes(Object solicitado, String salvo) {
        if (salvo == null) return false;

        try {
            return new BigDecimal(solicitado.toString()).compareTo(new BigDecimal(salvo)) == 0;
        } catch (NumberFormatException ignorado) {
            return solicitado.toString().strip().equalsIgnoreCase(salvo.strip());
        }
    }

    private boolean mesmoTrajeto(String origem, String destino, Map<String, String> dados) {
        return mesmoLocal(origem, dados.get("origem"))
                && mesmoLocal(destino, dados.get("destino"));
    }

    private static boolean mesmoLocal(String primeiro, String segundo) {
        return primeiro != null && segundo != null && !primeiro.isBlank() && !segundo.isBlank() && primeiro.strip().equalsIgnoreCase(segundo.strip());
    }

    private static Map<String, String> extrairDadosAssinatura(String assinatura) {
        Map<String, String> dados = new HashMap<>();

        if (assinatura == null || assinatura.isBlank()) return Map.of();

        // Uma assinatura ambígua não deve sobrescrever campos já registrados.
        String[] partes = assinatura.split("\\|", -1);
        for (int i = 0; i < partes.length; i++) {
            String parte = partes[i];
            if (i == 0 && parte.isEmpty()) continue;

            int separador = parte.indexOf('=');
            if (separador <= 0) return Map.of();

            String chave = parte.substring(0, separador);
            String valor = parte.substring(separador + 1);
            if (dados.putIfAbsent(chave, valor) != null) return Map.of();
        }

        return Map.copyOf(dados);
    }

    static double calcularSimilaridadeCosseno(float[] primeiro, float[] segundo) {
        validarVetorEmbedding(primeiro);
        validarVetorEmbedding(segundo);

        validarDimensoes(primeiro, segundo);

        double produtoEscalar = 0;
        double normaPrimeiro = 0;
        double normaSegundo = 0;

        for (int i = 0; i < primeiro.length; i++) {
            produtoEscalar += (double) primeiro[i] * segundo[i];
            normaPrimeiro += (double) primeiro[i] * primeiro[i];
            normaSegundo += (double) segundo[i] * segundo[i];
        }

        return produtoEscalar / Math.sqrt(normaPrimeiro * normaSegundo);
    }

    static float[] validarVetorEmbedding(float[] vetor) {
        if (vetor == null || vetor.length == 0) {
            throw new IllegalArgumentException("Embedding vazio");
        }

        double norma = 0;

        for (float valor : vetor) {
            if (!Float.isFinite(valor)) {
                throw new IllegalArgumentException("Embedding não finito");
            }

            norma += (double) valor * valor;
        }

        if (norma == 0) {
            throw new IllegalArgumentException("Embedding nulo");
        }

        return vetor;
    }

    // ========================================================
    // VALIDAÇÕES
    // ========================================================

    private static void validarLimiar(double limiar) {
        if (!Double.isFinite(limiar) || limiar < -1 || limiar > 1) {
            throw new IllegalArgumentException("Limiar de similaridade inválido");
        }
    }

    private static void validarDimensoes(float[] primeiro, float[] segundo) {
        if (primeiro.length != segundo.length) {
            throw new IllegalArgumentException("Dimensões de embeddings incompatíveis");
        }
    }

    public record Candidate(String signature, Map<String, String> data, String answer, double similarity) {}
}
