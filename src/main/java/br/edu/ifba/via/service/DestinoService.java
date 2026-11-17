package br.edu.ifba.via.service;

import br.edu.ifba.via.dto.DestinoSugerido;
import br.edu.ifba.via.helper.TextoHelper;
import br.edu.ifba.via.helper.ViagemHelper;
import br.edu.ifba.via.model.ContadorTokens;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Recomenda destinos e formata as opções; a escolha permanece no ChatService.
@Slf4j
@Service
@RequiredArgsConstructor
public class DestinoService {
    private final LlmService llmService;
    private final ObjectMapper conversorJson = new ObjectMapper();
    private final String promptDestinos = carregarPromptDestinos();

    // Recomenda destinos personalizados com base nos dados coletados.
    List<DestinoSugerido> recomendarDestinos(Sessao sessao, String contexto, ContadorTokens tokens) {
        log.info("Motor de inferência - Recomendação de destinos: {}", sessao.configuracaoIa.inferencia());

        String resposta = llmService.consultarLlm(sessao.configuracaoIa.inferencia(), promptDestinos,
                contexto, tokens);

        try {
            var json = conversorJson.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(TextoHelper.normalizarJson(resposta));
            if (json == null || !json.isObject()) throw new IllegalStateException("Resposta de destinos inválida");
            var destinos = json.path("destinos");

            if (!destinos.isArray()) {
                throw new IllegalStateException("Estrutura de destinos inválida");
            }

            List<DestinoSugerido> sugestoes = new ArrayList<>();
            Set<String> destinosProcessados = new HashSet<>(sessao.destinosRejeitados);

            for (var item : destinos) {
                if (!item.isObject() || !item.path("cidade").isTextual() || !item.path("pais").isTextual()
                        || !item.path("descricao").isTextual()) continue;
                DestinoSugerido destino = conversorJson.treeToValue(item, DestinoSugerido.class);

                if (!destinoSugeridoValido(destino)) continue;

                String cidade = ViagemHelper.normalizarDestino(destino.cidade());

                if (!destinosProcessados.add(cidade)) continue;

                sugestoes.add(destino);

                if (sugestoes.size() == 3) break;
            }

            log.info("Motor de inferência - Destinos recomendados: {}", sugestoes);
            return List.copyOf(sugestoes);
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível interpretar os destinos recomendados", e);
        }
    }

    // Impede a apresentação de recomendações incompletas.
    private boolean destinoSugeridoValido(DestinoSugerido destino) {
        return destino != null
                && destino.cidade() != null && !destino.cidade().isBlank()
                && destino.pais() != null && !destino.pais().isBlank()
                && destino.descricao() != null && !destino.descricao().isBlank();
    }

    // Formata as recomendações sem gerar um roteiro completo.
    String montarMensagemDestinos(Sessao sessao) {
        if (sessao.destinosSugeridos.isEmpty()) {
            return "Não encontrei destinos compatíveis. Digite 'outra sugestão' ou ajuste suas preferências.";
        }

        List<String> linhas = new ArrayList<>();

        for (int i = 0; i < sessao.destinosSugeridos.size(); i++) {
            var destino = sessao.destinosSugeridos.get(i);

            linhas.add((i + 1) + ". **" + destino.cidade() + " - " + destino.pais() + "**: " + destino.descricao());
        }

        return "Considerando suas preferências, selecionei os seguintes destinos:\n\n"
                + String.join("\n\n", linhas)
                + "\n\nQual destino você prefere? Responda com o número ou digite 'outra sugestão'.";
    }

    private static String carregarPromptDestinos() {
        try {
            return new ClassPathResource("prompt-destinos.md").getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Prompt de destinos não encontrado", e);
        }
    }
}
