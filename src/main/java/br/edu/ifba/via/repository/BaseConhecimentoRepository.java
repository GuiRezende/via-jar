package br.edu.ifba.via.repository;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class BaseConhecimentoRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public BaseConhecimentoRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Atualiza a base sem deixar exemplos de uma revisão anterior.
    @Transactional
    public void substituir(List<Exemplo> exemplos) {
        jdbc.update("DELETE FROM viajar.base_conhecimento", Map.of());
        for (int i = 0; i < exemplos.size(); i++) {
            Exemplo exemplo = exemplos.get(i);
            jdbc.update("""
                    INSERT INTO viajar.base_conhecimento
                        (id, pergunta, resposta, embedding_id, embedding_pergunta, ordem)
                    VALUES (:id, :pergunta, :resposta, CAST(:vetorId AS vector),
                            CAST(:vetorPergunta AS vector), :ordem)
                    """, Map.of("id", exemplo.id(), "pergunta", exemplo.pergunta(),
                    "resposta", exemplo.resposta(), "vetorId", Arrays.toString(exemplo.vetorId()),
                    "vetorPergunta", Arrays.toString(exemplo.vetorPergunta()), "ordem", i));
        }
    }

    // Compara os dois embeddings e preserva a ordem original em caso de empate.
    public Optional<Correspondencia> buscarMaisSemelhante(float[] vetor) {
        return jdbc.query("""
                SELECT id, pergunta, resposta,
                       GREATEST(sim_id, sim_pergunta) AS similaridade,
                       CASE WHEN sim_id >= sim_pergunta THEN 'ID' ELSE 'PERGUNTA' END AS origem
                FROM (
                    SELECT id, pergunta, resposta, ordem,
                           1 - (embedding_id <=> CAST(:vetor AS vector)) AS sim_id,
                           1 - (embedding_pergunta <=> CAST(:vetor AS vector)) AS sim_pergunta
                    FROM viajar.base_conhecimento
                ) AS candidatos
                ORDER BY similaridade DESC, ordem ASC
                LIMIT 1
                """, Map.of("vetor", Arrays.toString(vetor)),
                (rs, i) -> new Correspondencia(rs.getString("id"), rs.getString("pergunta"),
                        rs.getString("resposta"), rs.getDouble("similaridade"), rs.getString("origem")))
                .stream().findFirst();
    }

    public record Exemplo(String id, String pergunta, String resposta, float[] vetorId, float[] vetorPergunta) {}
    public record Correspondencia(String id, String pergunta, String resposta, double similaridade, String origem) {}
}
