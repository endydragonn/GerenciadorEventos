package com.gerenciador.eventos.stock;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gerenciador.eventos.reservationclient.api.EventCap;

/**
 * API INTERNA da capacidade do evento (issue #33, movida para o stock na #47), chamada pelo catalog do monólito. O {@code eventcap} nasce aqui por
 * chamada síncrona na criação do evento (read-your-writes) e é idempotente por {@code event_id}: o {@code EventCreated}
 * do tópico {@code catalog.event} reconcilia o que faltar ({@link CatalogReconciliationConsumer}).
 */
@RestController
@RequestMapping("/internal/eventcaps")
public class EventCapController {

    private final EventCapRepository repository;

    public EventCapController(EventCapRepository repository) {
        this.repository = repository;
    }

    /** Cria a capacidade (201); se o evento já tem {@code eventcap} não altera nada (200). */
    @PutMapping("/{eventId}")
    public ResponseEntity<Void> criar(@PathVariable Long eventId, @RequestBody EventCap cap) {
        cap.setEventId(eventId);
        validar(cap);
        return repository.create(cap) ? ResponseEntity.status(HttpStatus.CREATED).build() : ResponseEntity.ok().build();
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<EventCap> buscar(@PathVariable Long eventId) {
        EventCap cap = repository.findByEventId(eventId);
        return cap == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(cap);
    }

    /**
     * Várias de uma vez, com os ids no CORPO (a busca de eventos manda milhares: na query string estouraria o limite de header
     * do Tomcat, 8 KB); evento sem {@code eventcap} fica fora da lista.
     */
    @PostMapping("/batch")
    public List<EventCap> buscarVarias(@RequestBody List<Long> ids) {
        return List.copyOf(repository.findByEventIds(ids).values());
    }

    /**
     * Capacidade e janela de venda. Capacidade abaixo do que já foi reservado => 400 (CHECK do banco). Evento SEM
     * {@code eventcap} => 404 (nada foi atualizado: calar aqui perderia a edição, e a reconciliação pelo {@code EventCreated}
     * recriaria o {@code eventcap} com a capacidade antiga).
     */
    @PatchMapping("/{eventId}/policy")
    public ResponseEntity<Void> atualizarPolitica(@PathVariable Long eventId, @RequestBody EventCap politica) {
        validar(politica);
        return repository.updatePolicy(eventId, politica.getCapacity(), politica.getBuyTimeLimit())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** Apaga a capacidade do evento removido; 409 se existe reserva dele (o antigo RESTRICT). Sem {@code eventcap}: 204. */
    @DeleteMapping("/{eventId}")
    public ResponseEntity<String> apagar(@PathVariable Long eventId) {
        if (repository.deleteIfNoReservations(eventId)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body("Evento possui reservas: não pode ser removido (id=" + eventId + ")");
    }

    /** Capacidade válida: >= 1 (limitado) ou -1 (ilimitado), a mesma regra do catalog. */
    private static void validar(EventCap cap) {
        Integer capacity = cap.getCapacity();
        if (capacity == null || (capacity < 1 && capacity != EventCap.UNLIMITED)) {
            throw new IllegalArgumentException("capacity é obrigatória: >= 1, ou -1 para ilimitado");
        }
    }
}
