package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.gerenciador.eventos.shared.events.EventEnvelope;
import com.gerenciador.eventos.shared.events.Projection;

/**
 * Projeção assíncrona da disponibilidade (issue #29): guarda em {@code event_availability} só o nº de reservas ativas
 * por evento ({@code ativos}): {@code SlotReserved} +1, {@code ReservationExpired}/{@code ReservationCancelled} -1,
 * {@code ReservationConfirmed} 0. A disponibilidade é calculada na leitura ({@link EventAvailabilityRepository}).
 *
 * <p>Cada evento aplicado (inclusive o de delta 0) atualiza {@code applied_at} ({@code clock_timestamp()}, o commit do
 * consumer é logo depois) e {@code last_occurred_at} (o maior {@code occurredAt} visto: independente da ordem de
 * aplicação, então o replay o reproduz). Determinística exceto por {@code applied_at}, que o replay não compara.</p>
 */
public class EventAvailabilityProjection implements Projection {

    public static final String NOME = "reservation.availability";

    @Override
    public String name() {
        return NOME;
    }

    @Override
    public List<String> tables() {
        return List.of("event_availability");
    }

    @Override
    public void apply(Connection conn, EventEnvelope event, String schema) throws SQLException {
        int delta = delta(event.eventType());
        if (delta == Integer.MIN_VALUE) {
            return; // evento que esta projeção não conhece (compatibilidade para a frente)
        }
        String sql = "INSERT INTO " + schema + ".event_availability (event_id, ativos, last_occurred_at, applied_at) "
                + "VALUES (?, ?, ?, clock_timestamp()) "
                + "ON CONFLICT (event_id) DO UPDATE SET ativos = event_availability.ativos + EXCLUDED.ativos, "
                + "last_occurred_at = GREATEST(event_availability.last_occurred_at, EXCLUDED.last_occurred_at), "
                + "applied_at = clock_timestamp()";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, event.payload().get("eventId").asLong());
            ps.setInt(2, delta);
            ps.setObject(3, OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
            ps.executeUpdate();
        }
    }

    /** {@link Integer#MIN_VALUE} = tipo desconhecido. */
    private static int delta(String tipo) {
        return switch (tipo) {
            case ReservationEvents.SLOT_RESERVED -> 1;
            case ReservationEvents.EXPIRED, ReservationEvents.CANCELLED -> -1;
            case ReservationEvents.CONFIRMED -> 0;
            default -> Integer.MIN_VALUE;
        };
    }
}
