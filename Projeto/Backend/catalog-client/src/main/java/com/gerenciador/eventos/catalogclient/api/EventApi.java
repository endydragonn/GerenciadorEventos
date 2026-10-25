package com.gerenciador.eventos.catalogclient.api;

import java.util.List;

/**
 * Contrato do serviço {@code catalog} (issue #35): o mesmo {@code EventApi} que o módulo {@code catalog} do monólito expunha,
 * agora implementado dos dois lados da fronteira física — no serviço por {@code EventService} e no {@code gateway} pelo
 * {@code CatalogClient} (HTTP, {@code /internal/events/**}, um endpoint por método).
 *
 * <p>"Não encontrado" é {@link EventoNaoEncontradoException} (HTTP 404) onde o método não devolve {@code null}; falha do
 * serviço (rede, timeout, 5xx) é {@link CatalogUnavailableException}, nunca um resultado vazio ou {@code false}.</p>
 */
public interface EventApi {

    Event createEvent(Event e);

    /** @throws EventoNaoEncontradoException se o evento não existe */
    Event updateEvent(Event e);

    /** @throws EventoNaoEncontradoException se o evento não existe */
    Event findById(Long id);

    /** Cancela o evento; inexistente é no-op. Recusa (409, {@link EventoEmConflitoException}) se há reserva do evento. */
    void delete(Long id);

    String getValidationErrors(Event e);

    List<Event> searchEvents(String term);

    List<Event> findByCreatorId(Long creatorId);

    /** Existe evento com esse id? Não lança se não existir (diferente de {@link #findById}). */
    boolean exists(Long eventId);

    /** Bytes da imagem do evento, ou {@code null} se ele não existe ou não tem imagem (issue #35). */
    byte[] findImage(Long id);

    /** Grava a imagem do evento (fora de {@link #updateEvent}). @throws EventoNaoEncontradoException se ele não existe */
    void saveImage(Long id, byte[] imageData);

    /**
     * Só o {@code creator_id} do evento, sem blob nem hidratação (a checagem de dono do gateway, issue #35).
     * @return o id do criador, ou {@code null} se o evento não existe
     */
    Long findCreatorId(Long id);
}
