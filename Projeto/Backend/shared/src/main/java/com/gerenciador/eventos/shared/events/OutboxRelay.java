package com.gerenciador.eventos.shared.events;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Relay {@code event_outbox -> broker} (ADR Event-Backbone-Event-Store, seção 2). Ligado por
 * {@code events.relay.enabled}. Uma transação por lote:
 * <ol>
 *   <li>{@code pg_try_advisory_xact_lock}: um relay por vez (vários contextos/réplicas); lock de TRANSAÇÃO,
 *       compatível com o Supavisor em transaction mode. Quem não pega sai sem fazer nada;</li>
 *   <li>lê {@code event_outbox ⋈ event_store ORDER BY global_position LIMIT n}, SEM {@code SKIP LOCKED} (pular
 *       linhas quebraria a ordem por stream);</li>
 *   <li>envia ao broker e espera o ack (producer idempotente, {@code acks=all}, timeouts curtos);</li>
 *   <li>apaga o lote do outbox e comita. Falha no envio: rollback, o lote volta no próximo tick.</li>
 * </ol>
 * Publicação at-least-once (rollback depois de enviar = reenvio): o consumer é idempotente.
 * Tópico = {@code streamType}; chave = {@code partition_key} gravada no append; header {@code publishedAt}.
 */
@Component
@ConditionalOnProperty(name = "events.relay.enabled", havingValue = "true")
public class OutboxRelay {

    /** Chave do advisory lock do relay do schema {@code public} (constante arbitrária, só este código usa). */
    public static final long LOCK_KEY = 2_700_001L;

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long ACK_TIMEOUT_S = 10;

    private final DatabaseConnection db;
    private final EventStore eventStore;
    private final ObjectMapper json;
    private final KafkaTemplate<String, String> template;
    private final int batchSize;
    private final long lockKey;

    public OutboxRelay(DatabaseConnection db, EventStore eventStore, ObjectMapper json,
            KafkaTemplate<String, String> template, @Value("${events.relay.batch-size:200}") int batchSize) {
        this.db = db;
        this.eventStore = eventStore;
        this.json = json;
        this.template = template;
        this.batchSize = batchSize;
        this.lockKey = lockKey(eventStore.schema());
    }

    /**
     * Chave do advisory lock por schema: o lock é do BANCO, então os relays de serviços diferentes (cada um drena o
     * outbox do seu schema) não podem disputar a mesma chave, senão um esperaria o outro e inflaria o lag medido.
     * {@code public} mantém {@link #LOCK_KEY}.
     */
    static long lockKey(String schema) {
        return "public".equals(schema) ? LOCK_KEY : LOCK_KEY + 1 + (schema.hashCode() & 0xFFFF);
    }

    @Scheduled(fixedDelayString = "${events.relay.interval-ms:50}", initialDelayString = "${events.relay.interval-ms:50}")
    public void tick() {
        try {
            drain();
        } catch (Exception ex) {
            log.warn("[OutboxRelay] lote não publicado, volta no próximo tick: {}", ex.toString());
        }
    }

    /** Publica lotes até o outbox esvaziar (ou outro relay segurar o lock). Devolve quantos eventos publicou. */
    public int drain() throws Exception {
        int total = 0;
        int n;
        while ((n = publishBatch()) > 0) {
            total += n;
        }
        return total;
    }

    /** Um lote numa transação. 0 = outbox vazio ou outro relay com o lock. */
    int publishBatch() throws Exception {
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!tryLock(conn)) {
                    conn.rollback();
                    return 0;
                }
                List<Pendente> lote = ler(conn);
                if (lote.isEmpty()) {
                    conn.rollback();
                    return 0;
                }
                publicar(lote);
                apagar(conn, lote);
                conn.commit();
                return lote.size();
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private boolean tryLock(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")) {
            ps.setLong(1, lockKey);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private List<Pendente> ler(Connection conn) throws SQLException {
        String sql = "SELECT " + EventStore.COLUNAS + ", partition_key FROM "
                + eventStore.tabela("event_outbox") + " JOIN " + eventStore.tabela("event_store")
                + " USING (global_position) ORDER BY global_position LIMIT ?";
        List<Pendente> lote = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, batchSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lote.add(new Pendente(eventStore.envelope(rs), rs.getString(11)));
                }
            }
        }
        return lote;
    }

    /** Envia o lote inteiro (em ordem) e só então espera os acks: mesma chave = mesma partição, ordem preservada. */
    private void publicar(List<Pendente> lote) throws Exception {
        List<CompletableFuture<?>> acks = new ArrayList<>();
        for (Pendente p : lote) {
            ProducerRecord<String, String> msg = new ProducerRecord<>(
                    p.envelope().streamType(), null, p.chave(), json.writeValueAsString(p.envelope()));
            msg.headers().add(new RecordHeader("publishedAt",
                    Instant.now().toString().getBytes(StandardCharsets.UTF_8)));
            acks.add(template.send(msg));
        }
        for (CompletableFuture<?> ack : acks) {
            ack.get(ACK_TIMEOUT_S, TimeUnit.SECONDS);
        }
    }

    private void apagar(Connection conn, List<Pendente> lote) throws SQLException {
        Long[] posicoes = lote.stream().map(p -> p.envelope().globalPosition()).toArray(Long[]::new);
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM " + eventStore.tabela("event_outbox") + " WHERE global_position = ANY(?)")) {
            ps.setArray(1, conn.createArrayOf("bigint", posicoes));
            ps.executeUpdate();
        }
    }

    private record Pendente(EventEnvelope envelope, String chave) {
    }
}
