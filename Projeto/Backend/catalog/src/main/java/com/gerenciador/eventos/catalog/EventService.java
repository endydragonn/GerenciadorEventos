package com.gerenciador.eventos.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.gerenciador.eventos.catalogclient.api.Event;
import com.gerenciador.eventos.catalogclient.api.EventApi;
import com.gerenciador.eventos.catalogclient.api.EventoEmConflitoException;
import com.gerenciador.eventos.catalogclient.api.EventoNaoEncontradoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gerenciador.eventos.reservationclient.api.EventCap;
import com.gerenciador.eventos.reservationclient.api.EventCapacityApi;
import com.gerenciador.eventos.reservationclient.api.ReservationConflictException;

/**
 * Service - regras de negócio e validações para Event. No serviço {@code catalog} (issue #35) implementa o contrato
 * {@link EventApi} da lib {@code catalog-client}, exposto 1:1 pelo {@link EventController} em {@code /internal/events/**}.
 *
 * <p>Capacidade/vagas/janela de venda vivem no agregado isolado {@code eventcap}, do SERVIÇO reservation
 * ({@link EventCapacityApi}, chamadas HTTP síncronas); apresentadores em {@code eventpresenter}
 * ({@link EventPresenterRepository}). O POJO {@link Event} é hidratado com esses dados
 * ({@link #hydrate}) antes de sair do service.</p>
 */
@Service
public class EventService implements EventApi {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventCapacityApi eventCapacityApi;

    @Autowired
    private EventPresenterRepository eventPresenterRepository;

    @Autowired
    private CatalogEventWriter eventWriter;

    public Event createEvent(Event e) {
        applyDefaults(e);
        validateRequired(e);
        validateRules(e);
        validateDuplicatesOnCreate(e);
        Event saved = eventRepository.save(e); // EventCreated + event + eventpresenter, numa transação
        EventCap cap = new EventCap(saved.getEvent_id(), saved.getLot_quantity(), saved.getLot_quantity(),
                saved.getBuy_time_limit());
        // Criada: relê do serviço (os valores gravados, com a precisão do banco). Falhou: responde com os da requisição.
        return criarCapacidade(cap) ? hydrate(saved) : hydrate(saved, cap);
    }

    /**
     * Cria o {@code eventcap} no serviço reservation, por chamada síncrona DEPOIS do commit do evento (read-your-writes:
     * quem reserva logo depois encontra a vaga). Idempotente por {@code event_id}. NÃO há atomicidade com o commit do
     * evento: se a chamada falhar (serviço fora, rede), o evento JÁ existe sem capacidade, a criação responde normalmente com
     * os valores da requisição, e o reservation cria o {@code eventcap} sozinho a partir do {@code EventCreated} do tópico
     * {@code catalog.event} quando estiver de pé (reservar nesse intervalo falha com "Capacidade do evento não configurada").
     * @return {@code true} se criou (ou já existia); {@code false} se a chamada falhou
     */
    private boolean criarCapacidade(EventCap cap) {
        try {
            eventCapacityApi.create(cap);
            return true;
        } catch (RuntimeException ex) {
            log.error("[EventService] evento {} criado, mas o eventcap NÃO foi criado no reservation ({}): a reconciliação pelo "
                    + "EventCreated de catalog.event cria quando o serviço voltar", cap.getEventId(), ex.toString());
            return false;
        }
    }

    public Event updateEvent(Event e) {
        if (e.getEvent_id() == null) {
            throw new IllegalArgumentException("event_id é obrigatório");
        }
        if (eventRepository.findById(e.getEvent_id()) == null) {
            throw new EventoNaoEncontradoException(e.getEvent_id());
        }
        applyDefaults(e); // buy_time_limit não informado -> data do evento
        validateRequired(e);
        validateRules(e);
        validateDuplicatesOnUpdate(e);

        Event updated = eventRepository.update(e); // transação 1: EventUpdated + event (só metadados)

        // Transação 2: updatePolicy (chamada HTTP ao serviço reservation; o EventUpdated não leva capacity/buyTimeLimit).
        // Transação 3: PresenterAdded/PresenterRemoved + eventpresenter. As 3 seguem separadas como antes (issue #60).
        if (e.getLot_quantity() != null
                && !eventCapacityApi.updatePolicy(e.getEvent_id(), e.getLot_quantity(), e.getBuy_time_limit())) {
            // o evento está sem eventcap (a criação síncrona falhou e a reconciliação ainda não rodou): cria-o JÁ com os
            // valores novos (idempotente por event_id) em vez de perder a edição; a reconciliação, se vier depois, é no-op
            eventCapacityApi.create(new EventCap(e.getEvent_id(), e.getLot_quantity(), e.getLot_quantity(),
                    e.getBuy_time_limit()));
        }
        if (e.getPresenters() != null) {
            eventWriter.trocarApresentadores(e.getEvent_id(), e.getPresenters());
        }
        return hydrate(updated);
    }

    @Override
    public boolean exists(Long eventId) {
        return eventRepository.existsById(eventId); // SELECT 1, sem hidratar (caminho quente da reserva)
    }

    @Override
    public byte[] findImage(Long id) {
        return eventRepository.findImage(id);
    }

    @Override
    public void saveImage(Long id, byte[] imageData) {
        if (!eventRepository.saveImage(id, imageData)) {
            throw new EventoNaoEncontradoException(id);
        }
    }

    @Override
    public Long findCreatorId(Long id) {
        return eventRepository.findCreatorId(id); // SELECT creator_id, sem blob nem hidratação (checagem de dono do gateway)
    }

    public Event findById(Long id) {
        Event e = eventRepository.findById(id);
        if (e == null) throw new EventoNaoEncontradoException(id);
        return hydrate(e);
    }

    public Event findByName(String name) {
        Event e = eventRepository.findByName(name);
        if (e == null) throw new EventoNaoEncontradoException("Evento não encontrado: name=" + name);
        return hydrate(e);
    }

    /**
     * Cancela o evento. A capacidade é removida no reservation ANTES da transação do catalog, fora dela: a chamada HTTP não segura
     * conexão do pool nem o lock da linha de {@code event} durante o salto de rede, e a recusa (409, há reserva do evento em
     * qualquer status: o antigo {@code ON DELETE RESTRICT} de {@code walletevent}) acontece antes de qualquer escrita, com o evento
     * intacto. (Depois do commit não daria: o evento já teria sumido do catálogo com as reservas ainda de pé.) Janela de falha
     * documentada: se o reservation apagou o {@code eventcap} e a transação do catalog falha em seguida, o evento fica sem
     * {@code eventcap} (reservar nele falha com "Capacidade do evento não configurada"). Evento inexistente: no-op. O 409 do
     * reservation vira {@link EventoEmConflitoException} (o único 409 da API interna).
     */
    public void delete(Long id) {
        try {
            eventCapacityApi.delete(id);
        } catch (ReservationConflictException ex) {
            throw new EventoEmConflitoException(ex.getMessage());
        }
        eventRepository.delete(id); // EventCancelled + remoção (eventpresenter cai por CASCADE)
    }

    public boolean canBeCreated(Event e) {
        try {
            applyDefaults(e);
            validateRequired(e);
            validateRules(e);
            validateDuplicatesOnCreate(e);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public String getValidationErrors(Event e) {
        StringBuilder sb = new StringBuilder();
        applyDefaults(e);
        if (e.getCreator_id() == null) sb.append("creator_id é obrigatório. ");
        if (e.getEvent_name() == null || e.getEvent_name().isBlank()) sb.append("Nome do evento é obrigatório. ");
        if (e.getIs_EAD() == null) sb.append("is_EAD deve ser true/false. ");
        if (Boolean.FALSE.equals(e.getIs_EAD())) {
            if (e.getAddress() == null || e.getAddress().isBlank()) sb.append("Endereço é obrigatório para presencial. ");
        }
        if (e.getEvent_date() == null) sb.append("Data do evento é obrigatória. ");
        if (e.getBuy_time_limit() != null && e.getEvent_date() != null && e.getBuy_time_limit().isAfter(e.getEvent_date()))
            sb.append("buy_time_limit não pode ser após event_date. ");
        if (!isValidCapacity(e.getLot_quantity()))
            sb.append("capacity (lot_quantity) é obrigatória: >= 1, ou -1 para ilimitado. ");
        if (e.getEvent_name() != null && !e.getEvent_name().isBlank() && eventRepository.nameExists(e.getEvent_name())) {
            sb.append("Nome de evento já existente: ").append(e.getEvent_name()).append(". ");
        }
        // a existência do criador é conferida pelo gateway (UserApi, issue #35): o catalog só guarda o id
        return sb.toString().trim();
    }

    // ======= validações privadas =======
    private void validateRequired(Event e) {
        if (e.getCreator_id() == null) throw new IllegalArgumentException("creator_id é obrigatório");
        if (e.getEvent_name() == null || e.getEvent_name().isBlank()) throw new IllegalArgumentException("Nome do evento é obrigatório");
        if (e.getIs_EAD() == null) throw new IllegalArgumentException("is_EAD deve ser true/false");
        if (Boolean.FALSE.equals(e.getIs_EAD())) {
            if (e.getAddress() == null || e.getAddress().isBlank()) throw new IllegalArgumentException("Endereço é obrigatório para presencial");
        }
        if (e.getEvent_date() == null) throw new IllegalArgumentException("Data do evento é obrigatória");
    }

    private void validateRules(Event e) {
        if (e.getBuy_time_limit() != null && e.getEvent_date() != null && e.getBuy_time_limit().isAfter(e.getEvent_date()))
            throw new IllegalArgumentException("buy_time_limit não pode ser após event_date");
        if (!isValidCapacity(e.getLot_quantity()))
            throw new IllegalArgumentException("capacity (lot_quantity) é obrigatória: >= 1, ou -1 para ilimitado");
    }

    /** Capacidade válida: >= 1 (limitado) ou == -1 (ilimitado). */
    private boolean isValidCapacity(Integer capacity) {
        return capacity != null && (capacity >= 1 || capacity == EventCap.UNLIMITED);
    }

    // Defaults de domínio aplicados no POJO (sem depender do banco)
    private void applyDefaults(Event e) {
        if (e != null && e.getBuy_time_limit() == null && e.getEvent_date() != null) {
            e.setBuy_time_limit(e.getEvent_date());
        }
    }

    private void validateDuplicatesOnCreate(Event e) {
        if (e.getEvent_name() != null && !e.getEvent_name().isBlank() && eventRepository.nameExists(e.getEvent_name()))
            throw new IllegalArgumentException("Nome de evento já existente: " + e.getEvent_name());
    }

    private void validateDuplicatesOnUpdate(Event e) {
        if (e.getEvent_name() != null && !e.getEvent_name().isBlank()) {
            Event existing = eventRepository.findByName(e.getEvent_name());
            if (existing != null && !existing.getEvent_id().equals(e.getEvent_id())) {
                throw new IllegalArgumentException("Nome de evento já existente: " + e.getEvent_name());
            }
        }
    }

    public List<Event> searchEvents(String term) {
        return hydrateAll(eventRepository.searchByName(term)); // filtro no banco + hydrate em lote
    }

    public List<Event> findByCreatorId(Long creatorId) {
        if (creatorId == null) {
            return new ArrayList<>();
        }
        // filtro no banco (WHERE creator_id = ?) + UMA chamada em lote ao reservation (não uma por evento)
        return hydrateAll(eventRepository.findByCreatorId(creatorId));
    }

    /** Preenche capacity ({@code lot_quantity}), quant ({@code quantity}), buy_time_limit e
     *  presenters do POJO a partir dos agregados eventcap / eventpresenter. */
    public Event hydrate(Event e) {
        if (e == null || e.getEvent_id() == null) return e;
        return hydrate(e, eventCapacityApi.findByEventId(e.getEvent_id()));
    }

    private Event hydrate(Event e, EventCap cap) {
        if (cap != null) {
            e.setLot_quantity(cap.getCapacity());
            e.setQuantity(cap.getQuant() != null ? cap.getQuant() : 0);
            e.setBuy_time_limit(cap.getBuyTimeLimit());
        }
        e.setPresenters(eventPresenterRepository.findByEventId(e.getEvent_id()));
        return e;
    }

    /** Mesma lógica de {@link #hydrate}, mas com 2 consultas para a lista toda (sem N+1). */
    public List<Event> hydrateAll(List<Event> events) {
        if (events == null || events.isEmpty()) return events;
        List<Long> ids = events.stream().map(Event::getEvent_id).filter(java.util.Objects::nonNull).toList();
        Map<Long, EventCap> caps = eventCapacityApi.findByEventIds(ids);
        Map<Long, List<String>> presenters = eventPresenterRepository.findByEventIds(ids);
        for (Event e : events) {
            if (e.getEvent_id() == null) continue;
            EventCap cap = caps.get(e.getEvent_id());
            if (cap != null) {
                e.setLot_quantity(cap.getCapacity());
                e.setQuantity(cap.getQuant() != null ? cap.getQuant() : 0);
                e.setBuy_time_limit(cap.getBuyTimeLimit());
            }
            e.setPresenters(presenters.getOrDefault(e.getEvent_id(), new ArrayList<>()));
        }
        return events;
    }
}
