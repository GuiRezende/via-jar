package br.edu.ifba.via.service;

import br.edu.ifba.via.helper.TextoHelper;
import br.edu.ifba.via.repository.BaseConhecimentoRepository;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Gerencia a base de conhecimento do chatbot.
 *
 * Responsável pelo carregamento dos exemplos, busca semântica
 * e processamento dos templates de resposta.
 */
@Slf4j
@Service
@DependsOnDatabaseInitialization
public class BaseConhecimento {

    private static final String CAMINHO_BASE_CONHECIMENTO = "conhecimento/conhecimento.json";
    private static final int TAMANHO_MAXIMO_MENSAGEM = 90;
    private static final List<String> PREFIXOS_TEMPLATES = List.of("coleta_", "cache_", "confirmacao_");

    private final LocalEmbeddings embeddings;
    private final BaseConhecimentoRepository repository;
    private final double limiarSimilaridade;

    private final List<BaseConhecimentoRepository.Exemplo> exemplos = new ArrayList<>();
    private final Map<String, String> templates = new HashMap<>();
    private final Map<String, Set<String>> gatilhosPorTemplate = new HashMap<>();

    public BaseConhecimento(LocalEmbeddings embeddings, BaseConhecimentoRepository repository, @Value("${via.knowledge-threshold:0.78}") double limiarSimilaridade) {
        validarLimiar(limiarSimilaridade);
        this.embeddings = embeddings;
        this.repository = repository;
        this.limiarSimilaridade = limiarSimilaridade;
        carregarBaseConhecimento();
    }

    // ========================================================
    // BUSCA SEMÂNTICA
    // ========================================================

    // Prioriza a base de conhecimento antes de consultar a LLM.
    public String buscarRespostaLocal(String mensagem, boolean possuiContexto) {
        return buscarRespostaSemantica(mensagem, possuiContexto);
    }

    // Reconhece somente gatilhos integrais cadastrados para um template.
    public boolean correspondeGatilho(String id, String mensagem) {
        return gatilhosPorTemplate.getOrDefault(id, Set.of())
                .contains(TextoHelper.normalizarPergunta(mensagem));
    }

    // Busca uma resposta local sem interferir na coleta ou nas ofertas pendentes.
    public String buscarRespostaSemantica(String mensagem, boolean possuiContexto) {
        if (mensagem == null || mensagem.isBlank()) return null;
        String consulta = TextoHelper.normalizarPergunta(mensagem);
        if (deveIgnorarBusca(consulta, possuiContexto)) return null;

        return avaliarSimilaridade(mensagem).filter(Correspondencia::atingiuLimiar)
                .map(Correspondencia::resposta).orElse(null);
    }

    // Prioriza correspondências integrais e compara ambos os vetores nos demais casos.
    public Optional<Correspondencia> avaliarSimilaridade(String mensagem) {
        if (mensagem == null || mensagem.isBlank() || exemplos.isEmpty()) return Optional.empty();
        String consulta = TextoHelper.normalizarPergunta(mensagem);

        for (BaseConhecimentoRepository.Exemplo exemplo : exemplos) {
            if (consulta.equals(normalizarId(exemplo.id()))) return registrarCorrespondencia(exemplo, 1.0, "ID_EXATO");
        }
        for (BaseConhecimentoRepository.Exemplo exemplo : exemplos) {
            if (consulta.equals(TextoHelper.normalizarPergunta(exemplo.pergunta()))) return registrarCorrespondencia(exemplo, 1.0, "PERGUNTA_EXATA");
        }

        float[] vetorConsulta = embeddings.embed(consulta);
        return repository.buscarMaisSemelhante(vetorConsulta)
                .flatMap(candidato -> registrarCorrespondencia(candidato.id(), candidato.pergunta(),
                        candidato.resposta(), candidato.similaridade(), candidato.origem()));
    }

    // Registra o candidato e o campo responsável pela correspondência.
    private Optional<Correspondencia> registrarCorrespondencia(BaseConhecimentoRepository.Exemplo exemplo, double similaridade, String origem) {
        return registrarCorrespondencia(exemplo.id(), exemplo.pergunta(), exemplo.resposta(), similaridade, origem);
    }

    private Optional<Correspondencia> registrarCorrespondencia(String id, String pergunta, String resposta, double similaridade, String origem) {
        if (!Double.isFinite(similaridade)) return Optional.empty();
        boolean atingiuLimiar = similaridade >= limiarSimilaridade;
        log.info("Base de conhecimento - Candidato: {} | Origem: {} | Similaridade: {} | Limiar: {} | Aceito: {}",
                id, origem, String.format(Locale.ROOT, "%.4f", similaridade), limiarSimilaridade, atingiuLimiar);
        return Optional.of(new Correspondencia(id, pergunta, resposta, similaridade, atingiuLimiar));
    }

    private String normalizarId(String id) {
        return TextoHelper.normalizarPergunta(id.replace('_', ' '));
    }

    // Determina se a mensagem deve seguir para o fluxo normal
    private boolean deveIgnorarBusca(String mensagem, boolean possuiContexto) {
        return possuiContexto
                || mensagem.length() > TAMANHO_MAXIMO_MENSAGEM
                || TextoHelper.mensagemComposta(mensagem)
                || TextoHelper.contemRestricoes(mensagem)
                || TextoHelper.contemDadosPlanejamento(mensagem)
                || TextoHelper.contemNumeros(mensagem);
    }

    // ========================================================
    // CARREGAMENTO DA BASE DE CONHECIMENTO
    // ========================================================

    // Carrega a base de conhecimento durante a inicialização
    private void carregarBaseConhecimento() {
        try (InputStream fluxo = new ClassPathResource(CAMINHO_BASE_CONHECIMENTO).getInputStream()) {
            JsonNode dados = new ObjectMapper().readTree(fluxo);

            validarBaseConhecimento(dados);

            Set<String> identificadores = new HashSet<>();

            for (JsonNode registro : dados) {
                registrarEntrada(registro, identificadores);
            }
            repository.substituir(exemplos);
            log.info("Base de conhecimento - {} exemplos indexados no pgvector", exemplos.size());

        } catch (Exception e) {
            throw new IllegalStateException("Falha ao carregar base de conhecimento", e);
        }
    }

    // Extrai, valida e registra uma entrada da base.
    private void registrarEntrada(JsonNode registro, Set<String> identificadores) {
        String id = registro.path("id").asText().trim();
        String pergunta = registro.path("pergunta").asText().trim();
        String resposta = registro.path("resposta").asText().trim();

        validarEntrada(id, pergunta, resposta, identificadores);

        if (ehExemploSemantico(id)) {
            registrarExemplo(id, pergunta, resposta);
        }

        templates.put(id, resposta);
        registrarGatilhos(id, registro.path("gatilhos"));
    }

    // Carrega os gatilhos opcionais e valida seus tipos.
    private void registrarGatilhos(String id, JsonNode gatilhos) {
        if (gatilhos.isMissingNode()) return;
        if (!gatilhos.isArray()) {
            throw new IllegalArgumentException("Gatilhos inválidos: " + id);
        }
        Set<String> valores = new HashSet<>();
        for (JsonNode gatilho : gatilhos) {
            if (!gatilho.isTextual() || TextoHelper.normalizarPergunta(gatilho.asText()).isBlank()) {
                throw new IllegalArgumentException("Gatilho inválido: " + id);
            }
            valores.add(TextoHelper.normalizarPergunta(gatilho.asText()));
        }
        gatilhosPorTemplate.put(id, Set.copyOf(valores));
    }

    // Gera os dois vetores durante a inicialização.
    private void registrarExemplo(String id, String pergunta, String resposta) {
        float[] vetorId = embeddings.embed(normalizarId(id));
        float[] vetorPergunta = embeddings.embed(TextoHelper.normalizarPergunta(pergunta));
        exemplos.add(new BaseConhecimentoRepository.Exemplo(id, pergunta, resposta, vetorId, vetorPergunta));
    }

    // Verifica se uma entrada pode participar da busca semântica.
    private boolean ehExemploSemantico(String id) {
        return PREFIXOS_TEMPLATES.stream().noneMatch(id::startsWith);
    }

    // ========================================================
    // PROCESSAMENTO DE TEMPLATES
    // ========================================================

    // Recupera um template e substitui seus marcadores
    public String redenrizarTemplate(String id, Map<String, ?> valores) {
        String resposta = buscarTemplate(id);

        resposta = substituirMarcadores(resposta, valores);

        validarTemplate(id, resposta);

        return resposta;
    }

    // Recupera um template previamente cadastrado.
    private String buscarTemplate(String id) {
        String resposta = templates.get(id);

        if (resposta == null) {
            throw new IllegalArgumentException("Template inexistente: " + id);
        }

        return resposta;
    }

    // Substitui os marcadores do template pelos valores recebidos.
    private String substituirMarcadores(String template, Map<String, ?> valores) {
        String resposta = template;

        for (var entrada : valores.entrySet()) {
            String marcador = "{" + entrada.getKey() + "}";
            String valor = String.valueOf(entrada.getValue());

            resposta = resposta.replace(marcador, valor);
        }

        return resposta;
    }

    // ========================================================
    // VALIDAÇÕES
    // ========================================================

    // Valida o intervalo permitido para a similaridade cosseno.
    private void validarLimiar(double limiar) {
        if (!Double.isFinite(limiar) || limiar < -1 || limiar > 1) {
            throw new IllegalArgumentException("Limiar da base inválido");
        }
    }

    // Garante que o JSON contenha uma lista não vazia
    private void validarBaseConhecimento(JsonNode dados) {
        if (!dados.isArray() || dados.isEmpty()) {
            throw new IllegalArgumentException("Base de conhecimento vazia ou inválida");
        }
    }

    // Valida os campos obrigatórios de uma entrada
    private void validarEntrada(String id, String pergunta, String resposta, Set<String> identificadores) {
        if (id.isBlank() || pergunta.isBlank() || resposta.isBlank() || !identificadores.add(id)) {
            throw new IllegalArgumentException("Exemplo de conhecimento inválido ou duplicado: " + id);
        }
    }

    // Verifica se ainda existem marcadores não substituídos
    private void validarTemplate(String id, String resposta) {
        if (TextoHelper.possuiMarcadorTemplate(resposta)) {
            throw new IllegalArgumentException("Template incompleto: " + id);
        }
    }

    // ========================================================
    // MODELO INTERNO
    // ========================================================

    // Resultado da comparação, independente das regras de roteamento.
    public record Correspondencia(String id, String pergunta, String resposta, double similaridade, boolean atingiuLimiar) {}
}
