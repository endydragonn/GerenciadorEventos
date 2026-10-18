package com.gerenciador.eventos.catalog.api;

import java.util.List;

/**
 * Interface pública do módulo {@code catalog} (issue #23). Outros módulos e o {@code bff} só
 * chamam eventos por aqui, nunca por {@code EventService}/{@code EventRepository}.
 */
public interface EventApi {

    Event createEvent(Event e);

    Event updateEvent(Event e);

    Event findById(Long id);

    void delete(Long id);

    String getValidationErrors(Event e);

    List<Event> searchEvents(String term);

    List<Event> findByCreatorId(Long creatorId);

    /** Existe evento com esse id? Não lança se não existir (diferente de {@link #findById}). */
    boolean exists(Long eventId);
}
