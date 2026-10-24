package com.gerenciador.eventos.catalog.api;

import java.util.List;

/**
 * Interface pública do módulo {@code catalog} (issue #23). Outros módulos e o {@code gateway} só
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

    /** Bytes da imagem do evento, ou {@code null} se ele não existe ou não tem imagem (issue #35). */
    byte[] findImage(Long id);

    /** Grava a imagem do evento (fora de {@link #updateEvent}). Lança "Evento não encontrado" se ele não existe. */
    void saveImage(Long id, byte[] imageData);

    /** Só o {@code creator_id} (checagem de dono do gateway, sem blob nem hidratação), ou {@code null} se o evento não existe. */
    Long findCreatorId(Long id);
}
