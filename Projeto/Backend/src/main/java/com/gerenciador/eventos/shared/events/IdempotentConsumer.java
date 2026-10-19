package com.gerenciador.eventos.shared.events;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Base do consumer idempotente das projeções assíncronas (ADR Event-Backbone-Event-Store, seção 2). Ligado por
 * {@code events.consumer.enabled}. O listener de cada projeção só chama {@link #onMessage} (uma mensagem por
 * transação) ou {@link #onBatch} (um lote por transação, #146; é o que a projeção de disponibilidade usa).
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
 *
 * <p><b>Em lote</b> ({@link #onBatch}): o passo a passo acima vale para cada mensagem, na ordem, mas na MESMA
 * transação (um commit e um ack por lote, em vez de um por mensagem). Duplicata e várias versões do mesmo stream no
 * lote funcionam sem tratamento extra: a leitura do checkpoint, na mesma conexão, enxerga o avanço ainda não
 * comitado. Perder a corrida do CAS para outro aplicador desfaz e refaz o lote inteiro.</p>
 *
 * <p><b>Falha no meio do lote</b> (mensagem {@code i}: payload inválido, buraco, erro de banco/projeção): a transação
 * inteira é desfeita, as mensagens {@code 0..i-1} são reaplicadas e comitadas numa transação nova, e só então lança
 * {@link BatchListenerFailedException}{@code (i)}. O error handler comita o offset de {@code 0..i-1} (por isso o banco
 * já tem que ter comitado: o último evento de um stream nunca seria preenchido como "buraco") e reentrega só de
 * {@code i} em diante, aplicando a política da {@link EventsKafkaConfig} à causa; só a mensagem {@code i} vai ao DLT.
 * Nunca escapa exceção crua do lote: uma exceção crua cairia no handler de lote "genérico", que manda o lote inteiro
 * ao DLT quando o retry esgota e não chama o {@code RetryListener} por mensagem.</p>
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

    /**
     * Ponto de entrada do {@code @KafkaListener} em lote (#146): aplica as mensagens, em ordem, numa transação só e
     * dá o ack do lote depois do commit.
     *
     * @return um item por mensagem, na mesma ordem: o envelope se foi aplicada, ou vazio se descartada
     * @throws BatchListenerFailedException falha na mensagem de índice {@code i}; a causa é o que a
     *         {@link EventsKafkaConfig} classifica ({@link InvalidEventMessageException}, {@link EventGapException},
     *         {@link SQLException}...). As anteriores já estão comitadas no banco e o ack NÃO é dado (ver a doc da classe)
     */
    public List<Optional<EventEnvelope>> onBatch(Projection projection, List<String> values, Acknowledgment ack) {
        List<Optional<EventEnvelope>> resultado;
        try {
            resultado = aplicarLote(projection, values);
        } catch (FalhaNoLote f) {
            throw falhaComPrefixoComitado(projection, values, f);
        } catch (SQLException | RuntimeException ex) {
            throw new BatchListenerFailedException("Lote não comitado", ex, 0);
        }
        ack.acknowledge();
        return resultado;
    }

    /** Desfeito o lote, comita o que veio antes da mensagem culpada e devolve a exceção que o error handler espera. */
    private BatchListenerFailedException falhaComPrefixoComitado(Projection projection, List<String> values,
            FalhaNoLote f) {
        try {
            aplicarLote(projection, values.subList(0, f.indice));
        } catch (SQLException | RuntimeException ex) {
            return new BatchListenerFailedException("Prefixo do lote não comitado", ex, 0);
        }
        return new BatchListenerFailedException("Falha na mensagem de índice " + f.indice, f.getCause(), f.indice);
    }

    /**
     * Uma transação para as mensagens, em ordem (parse incluído: payload inválido é falha da mensagem como outra
     * qualquer). Devolve, por mensagem, o envelope se foi aplicada ou vazio se descartada.
     */
    private List<Optional<EventEnvelope>> aplicarLote(Projection projection, List<String> values) throws SQLException {
        if (values.isEmpty()) {
            return List.of();
        }
        return emTransacao(conn -> {
            List<Optional<EventEnvelope>> aplicadas = new ArrayList<>(values.size());
            for (int i = 0; i < values.size(); i++) {
                Boolean aplicou;
                EventEnvelope msg;
                try {
                    msg = parse(values.get(i));
                    aplicou = tentar(conn, projection, msg);
                } catch (SQLException | RuntimeException ex) {
                    throw new FalhaNoLote(i, ex);
                }
                if (aplicou == null) {
                    return null; // perdeu a corrida: desfaz o lote inteiro e refaz
                }
                aplicadas.add(aplicou ? Optional.of(msg) : Optional.empty());
            }
            return aplicadas;
        });
    }

    /** Devolve {@code true} se aplicou algum evento, {@code false} se descartou. Lança se não pode avançar. */
    public boolean process(Projection projection, EventEnvelope msg) throws SQLException {
        return emTransacao(conn -> tentar(conn, projection, msg));
    }

    /**
     * Roda o corpo numa transação e comita. Corpo que devolve {@code null} = perdeu a corrida do checkpoint para outro
     * aplicador: desfaz e repete. Termina: cada nova tentativa só acontece porque outro aplicador avançou o checkpoint.
     */
    private <T> T emTransacao(Corpo<T> corpo) throws SQLException {
        while (true) {
            try (Connection conn = db.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    T resultado = corpo.executar(conn);
                    if (resultado == null) {
                        conn.rollback();
                        continue;
                    }
                    conn.commit();
                    return resultado;
                } catch (SQLException | RuntimeException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }
    }

    @FunctionalInterface
    private interface Corpo<T> {
        T executar(Connection conn) throws SQLException;
    }

    /** A mensagem de índice {@code indice} do lote falhou com {@link #getCause()}; só atravessa o {@code emTransacao}. */
    private static final class FalhaNoLote extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int indice;

        FalhaNoLote(int indice, Throwable causa) {
            super(causa);
            this.indice = indice;
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
