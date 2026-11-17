package br.edu.ifba.via.repository;

import br.edu.ifba.via.model.RegistroAvaliacao;
import br.edu.ifba.via.service.AvaliadorRoteiroService.Status;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class HistoricoAvaliacaoRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json = criarConversorJson();

    public HistoricoAvaliacaoRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Registra uma tentativa; o identificador único impede duplicidade.
    public void registrar(RegistroAvaliacao registro) {
        jdbc.update("""
                INSERT INTO viajar.historico_avaliacoes
                    (id, id_processamento, id_sessao, data_hora, origem,
                     tentativa, status, nota, registro)
                VALUES (:id, :processamento, :sessao, :dataHora, :origem,
                        :tentativa, :status, :nota, CAST(:registro AS jsonb))
                """, parametros(registro));
    }

    // Permite importar o histórico antigo novamente sem duplicar registros.
    public int importar(RegistroAvaliacao registro) {
        return jdbc.update("""
                INSERT INTO viajar.historico_avaliacoes
                    (id, id_processamento, id_sessao, data_hora, origem,
                     tentativa, status, nota, registro)
                VALUES (:id, :processamento, :sessao, :dataHora, :origem,
                        :tentativa, :status, :nota, CAST(:registro AS jsonb))
                ON CONFLICT (id) DO NOTHING
                """, parametros(registro));
    }

    public List<RegistroAvaliacao> listar(Status status) {
        String sql = "SELECT registro FROM viajar.historico_avaliacoes";
        Map<String, ?> parametros = Map.of();
        if (status != null) {
            sql += " WHERE status = :status";
            parametros = Map.of("status", status.name());
        }
        return jdbc.query(sql + " ORDER BY data_hora DESC, id",
                parametros, (rs, i) -> desserializar(rs.getString("registro")));
    }

    public Optional<RegistroAvaliacao> buscarPorId(UUID id) {
        return jdbc.query("""
                SELECT registro FROM viajar.historico_avaliacoes WHERE id = :id
                """, Map.of("id", id), (rs, i) -> desserializar(rs.getString("registro")))
                .stream().findFirst();
    }

    public List<RegistroAvaliacao> buscarPorProcessamento(UUID idProcessamento) {
        return jdbc.query("""
                SELECT registro FROM viajar.historico_avaliacoes
                WHERE id_processamento = :id
                ORDER BY tentativa, data_hora, id
                """, Map.of("id", idProcessamento),
                (rs, i) -> desserializar(rs.getString("registro")));
    }

    private MapSqlParameterSource parametros(RegistroAvaliacao registro) {
        Objects.requireNonNull(registro, "Registro de avaliação obrigatório");
        Objects.requireNonNull(registro.avaliacao().status(), "Status da avaliação obrigatório");
        return new MapSqlParameterSource()
                .addValue("id", registro.id())
                .addValue("processamento", registro.idProcessamento())
                .addValue("sessao", registro.idSessao())
                .addValue("dataHora", registro.dataHora().atOffset(ZoneOffset.UTC))
                .addValue("origem", registro.origem().name())
                .addValue("tentativa", registro.tentativa())
                .addValue("status", registro.avaliacao().status().name())
                .addValue("nota", registro.avaliacao().nota(), Types.NUMERIC)
                .addValue("registro", serializar(registro));
    }

    private String serializar(RegistroAvaliacao registro) {
        try {
            return json.writeValueAsString(registro);
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível serializar a avaliação", e);
        }
    }

    private RegistroAvaliacao desserializar(String registro) {
        try {
            return json.readValue(registro, RegistroAvaliacao.class);
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível recuperar a avaliação", e);
        }
    }

    // Preserva Instant em ISO-8601 sem exigir dependência adicional do Jackson.
    private static ObjectMapper criarConversorJson() {
        SimpleModule modulo = new SimpleModule();
        modulo.addSerializer(Instant.class, new JsonSerializer<Instant>() {
            @Override
            public void serialize(Instant valor, JsonGenerator gerador, SerializerProvider provedor) throws IOException {
                gerador.writeString(valor.toString());
            }
        });
        modulo.addDeserializer(Instant.class, new JsonDeserializer<Instant>() {
            @Override
            public Instant deserialize(JsonParser parser, DeserializationContext contexto) throws IOException {
                if (!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING)) {
                    return (Instant) contexto.handleUnexpectedToken(Instant.class, parser);
                }
                try {
                    return Instant.parse(parser.getText());
                } catch (DateTimeParseException e) {
                    throw contexto.weirdStringException(parser.getText(), Instant.class, "Data inválida");
                }
            }
        });
        return new ObjectMapper().registerModule(modulo).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
}
