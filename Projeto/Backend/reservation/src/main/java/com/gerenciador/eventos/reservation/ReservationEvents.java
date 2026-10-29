package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gerenciador.eventos.shared.events.EventStore;
import com.gerenciador.eventos.shared.events.NovoEvento;

/**
 * Eventos do stream {@code reservation.wallet} (issue #29; ADR Event-Backbone-Event-Store): {@code SlotReserved},
 * {@code ReservationConfirmed}, {@code ReservationExpired}, {@code ReservationCancelled}.
 *
 * <p>{@code streamId = "userId:eventId"}; a chave de partição é o {@code event_id} (as reservas de um evento caem na
 * mesma partição). A {@code version} vem da própria guarda ({@code UPDATE walletevent ... SET version = version + 1
 * ... RETURNING version}), e o append só acontece quando a guarda afetou 1 linha, na MESMA {@link Connection}/transação.
 * Payload com ids e os valores já calculados ({@code expiresAt}); nunca dado pessoal.</p>
 *
 * <p><b>Saga stock + wallet (#47).</b> {@code SlotReserved}, {@code ReservationCancelled} e {@code ReservationExpired} levam o {@code holdId}
 * (aditivo; {@code schema_version} {@value #SCHEMA_COM_HOLD}): é o que o consumer do stock usa para devolver a vaga, idempotente por hold. Streams
 * anteriores à #47 (v1) não têm o campo; o consumer do stock os ignora e o replay os deixa fora da comparação do {@code hold_id}.</p>
 */
@Component
public class ReservationEvents {

    public static final String STREAM_TYPE = "reservation.wallet";
    public static final String SLOT_RESERVED = "SlotReserved";
    public static final String CONFIRMED = "ReservationConfirmed";
    public static final String EXPIRED = "ReservationExpired";
    public static final String CANCELLED = "ReservationCancelled";
    /** {@code schema_version} dos três eventos que levam o {@code holdId}. */
    public static final int SCHEMA_COM_HOLD = 2;

    private final EventStore eventStore;
    private final ObjectMapper json;

    public ReservationEvents(EventStore eventStore, ObjectMapper json) {
        this.eventStore = eventStore;
        this.json = json;
    }

    /** @return {@code false} se a versão já existia no stream (conflito otimista: o chamador faz rollback). */
    public boolean slotReserved(Connection conn, Long userId, Long eventId, int version, LocalDateTime expiresAt,
            String idempotencyKey, UUID holdId) throws SQLException {
        ObjectNode metadata = json.createObjectNode().put("idempotencyKey", idempotencyKey);
        return eventStore.append(conn, new NovoEvento(UUID.randomUUID(), STREAM_TYPE, streamId(userId, eventId),
                version, SLOT_RESERVED, SCHEMA_COM_HOLD,
                payload(userId, eventId).put("expiresAt", expiresAt.toString()).put("holdId", holdId.toString()), metadata,
                String.valueOf(eventId))).isPresent();
    }

    public void confirmed(Connection conn, Long userId, Long eventId, int version) throws SQLException {
        appendOuFalha(conn, userId, eventId, version, CONFIRMED, 1, payload(userId, eventId));
    }

    public void expired(Connection conn, Long userId, Long eventId, int version, UUID holdId) throws SQLException {
        appendOuFalha(conn, userId, eventId, version, EXPIRED, SCHEMA_COM_HOLD, comHold(userId, eventId, holdId));
    }

    public void cancelled(Connection conn, Long userId, Long eventId, int version, UUID holdId) throws SQLException {
        appendOuFalha(conn, userId, eventId, version, CANCELLED, SCHEMA_COM_HOLD, comHold(userId, eventId, holdId));
    }

    /**
     * Confirm/expire/cancel: a versão saiu da guarda que acabou de afetar 1 linha (travada até o commit), então um
     * conflito aqui não deveria acontecer; se acontecer, a exceção desfaz a transação inteira (guarda + vaga).
     */
    private void appendOuFalha(Connection conn, Long userId, Long eventId, int version, String tipo, int schemaVersion,
            ObjectNode payload) throws SQLException {
        boolean ok = eventStore.append(conn, new NovoEvento(UUID.randomUUID(), STREAM_TYPE, streamId(userId, eventId), version,
                tipo, schemaVersion, payload, json.createObjectNode(), String.valueOf(eventId))).isPresent();
        if (!ok) {
            throw new IllegalStateException("Conflito de versão ao appendar " + tipo + " v" + version + " em "
                    + streamId(userId, eventId) + ": a versão sai da guarda e não deveria colidir");
        }
    }

    private ObjectNode comHold(Long userId, Long eventId, UUID holdId) {
        ObjectNode p = payload(userId, eventId);
        if (holdId != null) {
            p.put("holdId", holdId.toString());
        }
        return p;
    }

    private ObjectNode payload(Long userId, Long eventId) {
        return json.createObjectNode().put("userId", userId).put("eventId", eventId);
    }

    public static String streamId(Long userId, Long eventId) {
        return userId + ":" + eventId;
    }
}
