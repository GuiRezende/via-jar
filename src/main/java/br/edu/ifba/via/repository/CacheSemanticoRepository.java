package br.edu.ifba.via.repository;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Repository
public class CacheSemanticoRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CacheSemanticoRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Recupera apenas entradas produzidas pelo modelo e revisão atuais.
    public List<Entrada> listar(String modelo, String revisao) {
        return jdbc.query("""
                SELECT id, assinatura, resposta #>> '{}' AS resposta
                FROM viajar.cache_semantico
                WHERE modelo_embeddings = :modelo AND revisao_modelo = :revisao
                ORDER BY id ASC
                """, Map.of("modelo", modelo, "revisao", revisao),
                (rs, i) -> new Entrada(rs.getLong("id"), rs.getString("assinatura"), rs.getString("resposta")));
    }

    // O serviço seleciona os IDs compatíveis antes de consultar a similaridade.
    public List<Pontuacao> ordenarPorSimilaridade(List<Long> ids, float[] vetor) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT id, 1 - (embedding <=> CAST(:vetor AS vector)) AS similaridade
                FROM viajar.cache_semantico
                WHERE id IN (:ids)
                ORDER BY similaridade DESC, id DESC
                """, Map.of("ids", ids, "vetor", Arrays.toString(vetor)),
                (rs, i) -> new Pontuacao(rs.getLong("id"), rs.getDouble("similaridade")));
    }

    // Preserva a resposta textual original, mesmo quando ela contém JSON.
    @Transactional
    public void armazenar(String texto, String assinatura, String resposta, float[] vetor,
                           String modelo, String revisao, int limite) {
        jdbc.update("""
                INSERT INTO viajar.cache_semantico
                    (texto, assinatura, resposta, embedding, modelo_embeddings, revisao_modelo)
                VALUES (:texto, :assinatura, to_jsonb(CAST(:resposta AS text)),
                        CAST(:vetor AS vector), :modelo, :revisao)
                """, Map.of("texto", texto, "assinatura", assinatura, "resposta", resposta,
                "vetor", Arrays.toString(vetor), "modelo", modelo, "revisao", revisao));
        jdbc.update("""
                DELETE FROM viajar.cache_semantico WHERE id IN (
                    SELECT id FROM viajar.cache_semantico ORDER BY id DESC OFFSET :limite
                )
                """, Map.of("limite", limite));
    }

    public record Entrada(long id, String assinatura, String resposta) {}
    public record Pontuacao(long id, double similaridade) {}
}
