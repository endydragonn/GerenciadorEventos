package com.gerenciador.eventos.stockclient.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;

/**
 * Capacidade do evento ({@code eventcap}, do serviço stock), do ponto de vista de quem CHAMA o serviço (o
 * {@code catalog} do monólito). Cada método é uma chamada HTTP síncrona à API interna: o {@code eventcap} nasce por
 * aqui na criação do evento (read-your-writes) e a criação é idempotente por {@code eventId}.
 */
public interface EventCapacityApi {

    /** Cria a capacidade do evento; repetir para o mesmo {@code eventId} não altera nada (idempotente). */
    void create(EventCap cap);

    /** @return a capacidade, ou {@code null} se o evento não tem {@code eventcap}. */
    EventCap findByEventId(Long eventId);

    /**
     * event_id -> capacidade, numa só chamada ({@code POST /internal/eventcaps/batch} com os ids no CORPO: dezenas de milhares de
     * ids na query string estourariam o limite de header do Tomcat). Eventos sem {@code eventcap} ficam fora do mapa.
     */
    Map<Long, EventCap> findByEventIds(Collection<Long> eventIds);

    /**
     * Atualiza capacidade/janela de venda.
     * @return {@code true} se atualizou; {@code false} se o evento NÃO tem {@code eventcap} (HTTP 404): nada foi atualizado, e o
     *         chamador decide (o catalog cria o {@code eventcap} com os valores novos, senão a reconciliação pelo
     *         {@code EventCreated} o recriaria depois com a capacidade ANTIGA)
     * @throws IllegalArgumentException se a nova capacidade ficaria abaixo do que já foi reservado (CHECK do banco)
     */
    boolean updatePolicy(Long eventId, Integer newCapacity, LocalDateTime buyTimeLimit);

    /**
     * Remove a capacidade do evento que está sendo apagado. Recusa (409, {@link IllegalStateException}) se existe
     * reserva do evento em qualquer status: é o antigo {@code ON DELETE RESTRICT} de {@code walletevent}, que deixou de
     * existir quando as tabelas ficaram em schemas diferentes. Sem {@code eventcap} é no-op.
     */
    void delete(Long eventId);
}
