package br.edu.ifba.via.service;

import br.edu.ifba.via.dto.Fonte;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/**
 * Responsável pela consulta de informações geográficas ao Wikidata
 * por meio do protocolo MCP.
 */
@Service
public class WikidataService {

    private static final String FERRAMENTA_BUSCA = "search_items";
    private static final String IDIOMA_BUSCA = "pt";
    private static final String URL_WIKIDATA = "https://www.wikidata.org/wiki/";
    private static final String NOME_FONTE = "Wikidata MCP";

    private static final Pattern PADRAO_ITEM = Pattern.compile("^(Q\\d+):\\s*(.+?)\\s+[—–-]\\s+(.+)$");
    private static final Pattern PADRAO_LOCALIDADE = Pattern.compile("^(municipio|cidade|capital|vila|localidade|comuna|city|town|estado|state|province|provincia|distrito|district)\\b.*");

    private static final Pattern PADRAO_ACENTUACAO = Pattern.compile("\\p{M}");
    private static final Pattern PADRAO_ESPACOS = Pattern.compile("\\s+");

    private final List<McpSyncClient> clientes;

    public WikidataService(List<McpSyncClient> clientes) {
        this.clientes = clientes;
    }

    /**
     * Consulta informações sobre o destino informado.
     *
     * Retorna uma fonte somente quando existe uma correspondência
     * única, evitando associar informações a localidades ambíguas.
     */
    public List<Fonte> consultarDestino(String destino) {
        McpSyncClient cliente = inicializarCliente();

        if (!ferramentaBuscaDisponivel(cliente)) {
            return List.of();
        }

        McpSchema.CallToolResult resultado = consultarDestino(cliente, destino);

        if (Boolean.TRUE.equals(resultado.isError())) {
            return List.of();
        }

        List<Fonte> fontes = extrairFontes(destino, resultado);

        return fontes.size() == 1 ? List.copyOf(fontes) : List.of();
    }

    // ========================================================
    // COMUNICAÇÃO MCP
    // ========================================================

    /**
     * Recupera e inicializa o primeiro cliente MCP configurado.
     */
    private McpSyncClient inicializarCliente() {
        McpSyncClient cliente = clientes.getFirst();
        cliente.initialize();

        return cliente;
    }

    /**
     * Verifica se o servidor MCP disponibiliza a ferramenta
     * necessária para pesquisar entidades no Wikidata.
     */
    private boolean ferramentaBuscaDisponivel(McpSyncClient cliente) {
        return cliente.listTools().tools().stream()
                .anyMatch(ferramenta -> ferramenta.name().equals(FERRAMENTA_BUSCA));
    }

    /**
     * Executa a ferramenta MCP de pesquisa utilizando o destino
     * informado e solicitando resultados em português.
     */
    private McpSchema.CallToolResult consultarDestino(McpSyncClient cliente, String destino) {
        Map<String, Object> argumentos = Map.of("query", destino, "lang", IDIOMA_BUSCA);

        return cliente.callTool(new McpSchema.CallToolRequest(FERRAMENTA_BUSCA, argumentos));
    }

    // ========================================================
    // PROCESSAMENTO DA RESPOSTA MCP
    // ========================================================

    /**
     * Processa os conteúdos textuais retornados pelo servidor MCP,
     * recuperando as fontes relacionadas ao destino solicitado.
     */
    private List<Fonte> extrairFontes(String destino, McpSchema.CallToolResult resultado) {
        List<Fonte> fontes = new ArrayList<>();

        for (var conteudo : resultado.content()) {
            if (conteudo instanceof McpSchema.TextContent texto) {
                fontes.addAll(parseText(destino, texto.text()));
            }
        }

        return fontes;
    }

    /**
     * Interpreta as linhas retornadas pelo Wikidata e recupera
     * somente entidades geográficas compatíveis com o destino.
     *
     * Formato esperado:
     * Q36947: Salvador — município brasileiro
     */
    static List<Fonte> parseText(String destino, String texto) {
        List<Fonte> fontes = new ArrayList<>();

        for (String linha : texto.split("\\R")) {
            Matcher correspondencia = PADRAO_ITEM.matcher(linha.trim());

            if (!correspondencia.matches()) {
                continue;
            }

            String nome = correspondencia.group(2).trim();
            String descricao = correspondencia.group(3).trim();

            if (!localidadeCompativel(destino, nome, descricao)) {
                continue;
            }

            fontes.add(criarFonte(correspondencia.group(1), nome, descricao));
        }

        return fontes;
    }

    /**
     * Verifica se a entidade possui o mesmo nome do destino
     * solicitado e se sua descrição representa uma localidade.
     *
     * Evita considerar entidades homônimas, como pessoas,
     * empresas ou outras entidades não geográficas.
     */
    private static boolean localidadeCompativel(String destino, String nome, String descricao) {
        return normalizar(nome).equals(normalizar(destino))
                && PADRAO_LOCALIDADE.matcher(normalizar(descricao)).matches();
    }

    /**
     * Constrói a fonte de referência utilizando o identificador
     * único da entidade no Wikidata.
     */
    private static Fonte criarFonte(String id, String nome, String descricao) {
        return new Fonte(id, nome, descricao, URL_WIKIDATA + id, NOME_FONTE);
    }

    // ========================================================
    // NORMALIZAÇÃO
    // ========================================================

    /**
     * Normaliza textos para comparação de localidades,
     * removendo acentos, espaços excedentes e diferenças
     * entre letras maiúsculas e minúsculas.
     */
    private static String normalizar(String valor) {
        String texto = Normalizer.normalize(valor.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);

        texto = PADRAO_ACENTUACAO.matcher(texto).replaceAll("");

        return PADRAO_ESPACOS.matcher(texto).replaceAll(" ").trim();
    }
}