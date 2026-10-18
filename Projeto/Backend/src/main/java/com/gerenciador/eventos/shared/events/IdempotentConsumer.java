package com.gerenciador.eventos.shared.events;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Base do consumer idempotente das projeções assíncronas (ADR Event-Backbone-Event-Store, seção 2). Ligado por
 * {@code events.consumer.enabled}. O listener de cada projeção (#28/#29) só chama {@link #onMessage}.
 *
 * <p>Por mensagem, numa transação de banco só (checkpoint + projeção juntos; o offset só é comitado depois, por ack
 * manual):</p>
 * <ol>
 *   <li>confere o {@code eventId} contra o {@code event_store} em {@code (stream_type, stream_id, version)};
 *       inexistente ou diferente = descarta (sobra de teste anterior, depois de um TRUNCATE);</li>
 *   <li>{@code version <= checkpoint} = reenvio, descarta;</li>
 *   <li>senão relê do {@code event_store} as versões {@code checkpoint+1 .. version} (a própria mensagem e, se
 *       houver, o buraco antes dela) e, para cada uma, avança o checkpoint por CAS
 *       ({@code UPDATE ... WHERE version = v-1}, ou {@code INSERT ... ON CONFLICT DO NOTHING} na v1) e SÓ SE o
 *       rowcount for 1 aplica a projeção. Dois aplicadores concorrentes nunca contam o mesmo evento duas vezes;</li>
 *   <li>buraco que não existe nem no {@code event_store}: lança {@link EventGapException}, sem avançar nada;
 *       retry limitado e, esgotado, o dead-letter topic ({@link EventsKafkaConfig}). Já erro de banco/conexão
 *       ({@link SQLException}) é transitório: retry sem limite, nunca DLT.</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "events.consumer.enabled", havingValue = "true")
public class IdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

    private final DatabaseConnection db;
    private final EventStore eventStore;
    private final ObjectMapper json;

    public IdempotentConsumer(DatabaseConnection db, EventStore eventStore, ObjectMapper json) {
        this.db = db;
        this.eventStore = eventStore;
        this.json = json;
    }

    /**
     * Ponto de entrada do {@code @KafkaListener}: processa a mensagem e só então dá o ack (offset).
     *
     * @return o envelope da mensagem se ela foi aplicada, ou vazio se foi descartada (reenvio ou {@code eventId}
     *         fora do {@code event_store}); o listener usa para registrar o lag
     * @throws InvalidEventMessageException payload que não é um envelope válido (vai direto para o DLT)
     * @throws EventGapException            buraco irrecuperável no stream (retry limitado e depois DLT)
     * @throws SQLException                 falha transitória de banco/conexão (retry sem limite, nunca DLT)
     */
    public Optional<EventEnvelope> onMessage(Projection projection, String value, Acknowledgment ack)
            throws SQLException {
        EventEnvelope msg = parse(value);
        boolean aplicou = process(projection, msg);
        ack.acknowledge();
        return aplicou ? Optional.of(msg) : Optional.empty();
    }

    private EventEnvelope parse(String value) {
        if (value == null) {
            throw new InvalidEventMessageException("Mensagem sem corpo", null);
        }
        try {
            EventEnvelope e = json.readValue(value, EventEnvelope.class);
            if (e == null || e.eventId() == null || e.streamType() == null || e.streamId() == null || e.version() < 1) {
                throw new InvalidEventMessageException("Envelope sem campos obrigatórios", null);
            }
            return e;
        } catch (JsonProcessingException ex) {
            throw new InvalidEventMessageException("Mensagem não é um envelope de evento válido", ex);
        }
    }

    /** Devolve {@code true} se aplicou algum evento, {@code false} se descartou. Lança se não pode avançar. */
    public boolean process(Projection projection, EventEnvelope msg) throws SQLException {
        // Termina: cada nova tentativa só acontece porque outro aplicador avançou o checkpoint (limitado por msg.version).
        while (true) {
            try (Connection conn = db.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    Boolean aplicou = tentar(conn, projection, msg);
                    if (aplicou == null) {
                        conn.rollback();
                        continue;
                    }
                    conn.commit();
                    return aplicou;
                } catch (SQLException | RuntimeException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }
    }

    /** {@code null} = perdeu a corrida do checkpoint para outro aplicador (reler e tentar de novo). */
    private Boolean tentar(Connection conn, Projection projection, EventEnvelope msg) throws SQLException {
        if (!eventIdConfere(conn, msg)) {
            log.debug("[IdempotentConsumer] descartada (eventId fora do event_store): {}/{} v{}",
                    msg.streamType(), msg.streamId(), msg.version());
            return false;
        }
        int ultima = checkpoint(conn, projection.name(), msg);
        if (msg.version() <= ultima) {
            return false;
        }
        List<EventEnvelope> faltam = eventStore.readRange(conn, msg.streamType(), msg.streamId(), ultima + 1, msg.version());
        if (faltam.size() != msg.version() - ultima) {
            throw new EventGapException("Buraco no stream " + msg.streamType() + "/" + msg.streamId()
                    + ": checkpoint " + ultima + ", mensagem v" + msg.version() + ", mas só " + faltam.size()
                    + " versão(ões) no event_store");
        }
        for (EventEnvelope e : faltam) {
            if (!avancar(conn, projection.name(), e)) {
                return null;
            }
            projection.apply(conn, e, "public");
        }
        return true;
    }

    private boolean eventIdConfere(Connection conn, EventEnvelope msg) throws SQLException {
        String sql = "SELECT event_id FROM public.event_store WHERE stream_type = ? AND stream_id = ? AND version = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, msg.streamType());
            ps.setString(2, msg.streamId());
            ps.setInt(3, msg.version());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && msg.eventId().equals(rs.getObject(1, UUID.class));
            }
        }
    }

    private int checkpoint(Connection conn, String projecao, EventEnvelope msg) throws SQLException {
        String sql = "SELECT version FROM public.projection_checkpoint "
                + "WHERE projecao = ? AND stream_type = ? AND stream_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projecao);
            ps.setString(2, msg.streamType());
            ps.setString(3, msg.streamId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** CAS do checkpoint: rowcount 1 = esta transação é quem aplica a versão. */
    private boolean avancar(Connection conn, String projecao, EventEnvelope e) throws SQLException {
        String sql = e.version() == 1
                ? "INSERT INTO public.projection_checkpoint (projecao, stream_type, stream_id, version) "
                        + "VALUES (?, ?, ?, 1) ON CONFLICT DO NOTHING"
                : "UPDATE public.projection_checkpoint SET version = ?, updated_at = clock_timestamp() "
                        + "WHERE projecao = ? AND stream_type = ? AND stream_id = ? AND version = ? - 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            if (e.version() != 1) {
                ps.setInt(i++, e.version());
            }
            ps.setString(i++, projecao);
            ps.setString(i++, e.streamType());
            ps.setString(i++, e.streamId());
            if (e.version() != 1) {
                ps.setInt(i, e.version());
            }
            return ps.executeUpdate() == 1;
        }
    }
}
