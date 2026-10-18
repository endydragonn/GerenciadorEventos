package com.gerenciador.eventos.reservation.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;

/**
 * Interface pública do módulo {@code reservation} para o agregado {@code eventcap} (dono:
 * reservation). O {@code catalog} cria o evento e a capacidade na mesma transação JDBC, por
 * isso {@link #create} recebe a {@link Connection} do chamador; a hidratação do evento lê a
 * capacidade por {@link #findByEventId}/{@link #findByEventIds}.
 */
public interface EventCapacityApi {

    /** Cria a capacidade do evento na transação do chamador. */
    void create(Connection conn, EventCap cap) throws SQLException;

    EventCap findByEventId(Long eventId);

    /** event_id -> capacidade, numa só consulta. */
    Map<Long, EventCap> findByEventIds(Collection<Long> eventIds);

    void updatePolicy(Long eventId, Integer newCapacity, LocalDateTime buyTimeLimit);
}
