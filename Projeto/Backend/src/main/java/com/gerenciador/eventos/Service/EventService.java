package com.gerenciador.eventos.Service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.gerenciador.eventos.POJO.Event;
import com.gerenciador.eventos.POJO.EventCap;
import com.gerenciador.eventos.POJO.User;
import com.gerenciador.eventos.Repository.EventCapRepository;
import com.gerenciador.eventos.Repository.EventPresenterRepository;
import com.gerenciador.eventos.Repository.EventRepository;
import com.gerenciador.eventos.Repository.UserRepository;

/**
 * Service - regras de negócio e validações para Event.
 *
 * <p>Capacidade/vagas/janela de venda vivem no agregado isolado {@code eventcap}
 * ({@link EventCapRepository}); apresentadores em {@code eventpresenter}
 * ({@link EventPresenterRepository}). O POJO {@link Event} é hidratado com esses dados
 * ({@link #hydrate}) antes de sair do service.</p>
 */
@Service
public class EventService {

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EventCapRepository eventCapRepository;

    @Autowired
    private EventPresenterRepository eventPresenterRepository;

    public Event createEvent(Event e) {
        applyDefaults(e);
        validateRequired(e);
        validateRules(e);
        validateDuplicatesOnCreate(e);
        Event saved = eventRepository.save(e); // grava event + eventcap + eventpresenter
        return hydrate(saved);
    }

    public Event updateEvent(Event e) {
        if (e.getEvent_id() == null) {
            throw new IllegalArgumentException("event_id é obrigatório");
        }
        if (eventRepository.findById(e.getEvent_id()) == null) {
            throw new RuntimeException("Evento não encontrado: id=" + e.getEvent_id());
        }
        applyDefaults(e); // buy_time_limit não informado -> data do evento
        validateRequired(e);
        validateRules(e);
        validateDuplicatesOnUpdate(e);

        Event updated = eventRepository.update(e); // só metadados

        if (e.getLot_quantity() != null) {
            eventCapRepository.updatePolicy(e.getEvent_id(), e.getLot_quantity(), e.getBuy_time_limit());
        }
        if (e.getPresenters() != null) {
            eventPresenterRepository.replaceAll(e.getEvent_id(), e.getPresenters());
        }
        return hydrate(updated);
    }

    public Event findById(Long id) {
        Event e = eventRepository.findById(id);
        if (e == null) throw new RuntimeException("Evento não encontrado: id=" + id);
        return hydrate(e);
    }

    public Event findByName(String name) {
        Event e = eventRepository.findByName(name);
        if (e == null) throw new RuntimeException("Evento não encontrado: name=" + name);
        return hydrate(e);
    }

    public void delete(Long id) {
        eventRepository.delete(id); // eventcap/eventpresenter caem por ON DELETE CASCADE
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
        if (e.getCreator_id() != null) {
            User u = userRepository.findById(e.getCreator_id());
            if (u == null) sb.append("creator_id não existe em users. ");
        }
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
        if (e.getCreator_id() != null) {
            User u = userRepository.findById(e.getCreator_id());
            if (u == null) throw new IllegalArgumentException("creator_id não existe");
        }
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
        List<Event> all = eventRepository.findAll();
        List<Event> base;
        if (term == null || term.isBlank()) {
            base = all;
        } else {
            String q = term.toLowerCase();
            base = new ArrayList<>();
            for (Event e : all) {
                if (e.getEvent_name() != null && e.getEvent_name().toLowerCase().contains(q)) {
                    base.add(e);
                }
            }
        }
        base.forEach(this::hydrate);
        return base;
    }

    public List<Event> findByCreatorId(Long creatorId) {
        if (creatorId == null) {
            return new ArrayList<>();
        }
        List<Event> result = new ArrayList<>();
        for (Event e : eventRepository.findAll()) {
            if (e.getCreator_id() != null && e.getCreator_id().equals(creatorId)) {
                result.add(hydrate(e));
            }
        }
        return result;
    }

    /** Preenche capacity ({@code lot_quantity}), quant ({@code quantity}), buy_time_limit e
     *  presenters do POJO a partir dos agregados eventcap / eventpresenter. */
    public Event hydrate(Event e) {
        if (e == null || e.getEvent_id() == null) return e;
        EventCap cap = eventCapRepository.findByEventId(e.getEvent_id());
        if (cap != null) {
            e.setLot_quantity(cap.getCapacity());
            e.setQuantity(cap.getQuant() != null ? cap.getQuant() : 0);
            e.setBuy_time_limit(cap.getBuyTimeLimit());
        }
        e.setPresenters(eventPresenterRepository.findByEventId(e.getEvent_id()));
        return e;
    }
}
