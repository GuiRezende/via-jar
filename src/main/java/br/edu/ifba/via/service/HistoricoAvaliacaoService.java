package br.edu.ifba.via.service;

import br.edu.ifba.via.model.RegistroAvaliacao;
import br.edu.ifba.via.repository.HistoricoAvaliacaoRepository;
import br.edu.ifba.via.service.AvaliadorRoteiroService.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HistoricoAvaliacaoService {

    private final HistoricoAvaliacaoRepository repository;

    public void registrar(RegistroAvaliacao registro) {
        Objects.requireNonNull(registro, "Registro de avaliação obrigatório");
        Objects.requireNonNull(registro.avaliacao().status(), "Status da avaliação obrigatório");
        try {
            repository.registrar(registro);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("Avaliação já registrada: " + registro.id(), e);
        }
    }

    public List<RegistroAvaliacao> listar() {
        return listar(null);
    }

    // Um status nulo representa uma consulta sem filtro.
    public List<RegistroAvaliacao> listar(Status status) {
        return repository.listar(status);
    }

    public Optional<RegistroAvaliacao> buscarPorId(UUID id) {
        return repository.buscarPorId(Objects.requireNonNull(id, "Identificador obrigatório"));
    }

    public List<RegistroAvaliacao> buscarPorProcessamento(UUID idProcessamento) {
        return repository.buscarPorProcessamento(Objects.requireNonNull(idProcessamento,
                "Identificador do processamento obrigatório"));
    }
}
