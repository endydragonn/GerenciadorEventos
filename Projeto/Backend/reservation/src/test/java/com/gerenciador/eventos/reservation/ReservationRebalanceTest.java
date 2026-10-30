package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.BatchAcknowledgingMessageListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.TestSupport.EventsTestSupport;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.TestSupport.KafkaDeTeste;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventEnvelope;
import com.gerenciador.eventos.shared.events.EventStore;
import com.gerenciador.eventos.shared.events.EventsKafkaConfig;
import com.gerenciador.eventos.shared.events.IdempotentConsumer;
import com.gerenciador.eventos.shared.events.NovoEvento;
import com.gerenciador.eventos.shared.events.Projection;

/**
 * Réplicas do {@code reservation} consumindo a projeção de disponibilidade (autoescala, issue #112), Postgres e Redpanda reais. O
 * {@code IdempotentConsumer} e a política de erro ({@code EventsKafkaConfig}) são os REAIS, montados na mão sobre tópico e group de TESTE
 * (prefixo {@code t112-}, 3 partições, criados aqui; o {@code reservation.wallet} real tem 1 e não é tocado). Os eventos são appendados no
 * {@code reservation.event_store} ANTES de publicar: mensagem cujo {@code eventId} não está no store é descartada em silêncio e daria um teste
 * verde com {@code ativos} = 0.
 *
 * <ul>
 *   <li>{@link #rebalanceNaoAplicaAProjecaoDuasVezesECadaCheckpointFicaNaUltimaVersao}: A e B no mesmo group, B entra e A sai no meio do fluxo,
 *       e os offsets de A nunca são comitados (A "morre" depois do commit do banco e antes do commit do offset: é a janela at-least-once do
 *       rebalance), então B reentrega o que A já aplicou;</li>
 *   <li>{@link #versoesForaDeOrdemEmParticoesDiferentesSaoPreenchidasUmaVezSo}: o rehash único das chaves (partições novas): as versões de um
 *       stream chegam fora de ordem por partições diferentes e o checkpoint por stream preenche o buraco e descarta o resto;</li>
 *   <li>{@link #deadlockDeCheckpointNoRebalanceEhRepetidoNaoTravaAParticaoNemVaiAoDlt}: dois lotes em ordens opostas de stream dão 40P01.</li>
 * </ul>
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
public class ReservationRebalanceTest {

    private static final String RODADA = UUID.randomUUID().toString().substring(0, 8);
    private static final long BASE_EVENTO = 2_112_000_000L; // event_id é integer (máx. 2.147.483.647)

    @Autowired private KafkaTemplate<String, String> template;
    @Autowired private EventStore eventStore;
    @Autowired private DatabaseConnection db;
    @Autowired private ObjectMapper json;
    @org.springframework.beans.factory.annotation.Value("${spring.kafka.bootstrap-servers}") private String bootstrap;

    private IdempotentConsumer consumer;

    @BeforeEach
    void preparar() throws Exception {
        EventsTestSupport.limpar(db);
        consumer = new IdempotentConsumer(db, eventStore, json);
    }

    // ------------------------------------------------------------------ helpers

    private EventEnvelope append(long userId, long eventId, int version, String tipo) throws Exception {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            EventEnvelope e = eventStore.append(c, NovoEvento.of(ReservationEvents.STREAM_TYPE, ReservationEvents.streamId(userId, eventId),
                    version, tipo, json.createObjectNode().put("userId", userId).put("eventId", eventId),
                    String.valueOf(eventId))).orElseThrow();
            c.commit();
            return e;
        }
    }

    private Long ativos(long eventId) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT ativos FROM reservation.event_availability WHERE event_id = ?")) {
            ps.setLong(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private int checkpoint(long userId, long eventId) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT version FROM reservation.projection_checkpoint WHERE projecao = ? AND stream_type = ? AND stream_id = ?")) {
            ps.setString(1, EventAvailabilityProjection.NOME);
            ps.setString(2, ReservationEvents.STREAM_TYPE);
            ps.setString(3, ReservationEvents.streamId(userId, eventId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private int ckSeguro(long userId, long eventId) {
        try {
            return checkpoint(userId, eventId);
        } catch (Exception ex) {
            return -1;
        }
    }

    private void publicar(String topico, int particao, EventEnvelope e) throws Exception {
        template.send(new ProducerRecord<>(topico, particao, String.valueOf(e.payload().get("eventId").asLong()),
                json.writeValueAsString(e))).get(10, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private DefaultErrorHandler politicaDeErroReal() {
        return new EventsKafkaConfig().eventsErrorHandler((KafkaOperations<Object, Object>) (KafkaOperations<?, ?>) template,
                200, 2, 10);
    }

    private static boolean temSqlState(Throwable ex, String estado) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && estado.equals(s.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ rebalance

    @Test
    void rebalanceNaoAplicaAProjecaoDuasVezesECadaCheckpointFicaNaUltimaVersao() throws Exception {
        String topico = "t112-wallet-" + RODADA;
        String grupo = "t112-wallet-grupo-" + RODADA;
        KafkaDeTeste.criarTopico(bootstrap, topico, 3);
        // 9 eventos x 3 usuários: u1 reserva+confirma (+1), u2 reserva+cancela (0), u3 só reserva (+1) => ativos = 2 por evento
        List<EventEnvelope> v1 = new ArrayList<>();
        List<EventEnvelope> v2 = new ArrayList<>();
        for (long k = 0; k < 9; k++) {
            long ev = BASE_EVENTO + k;
            v1.add(append(1, ev, 1, ReservationEvents.SLOT_RESERVED));
            v1.add(append(2, ev, 1, ReservationEvents.SLOT_RESERVED));
            v1.add(append(3, ev, 1, ReservationEvents.SLOT_RESERVED));
            v2.add(append(1, ev, 2, ReservationEvents.CONFIRMED));
            v2.add(append(2, ev, 2, ReservationEvents.CANCELLED));
        }
        int mensagens = v1.size() + v2.size();

        AtomicInteger entregues = new AtomicInteger();
        AtomicInteger entreguesA = new AtomicInteger();
        AtomicInteger entreguesB = new AtomicInteger();
        Projection projecao = new EventAvailabilityProjection();
        // A: aplica no banco e NUNCA comita o offset (morre depois do commit do banco, antes do commit do offset)
        Acknowledgment semAck = () -> { };
        BatchAcknowledgingMessageListener<String, String> ouvinteA = (records, ack) -> {
            entregues.addAndGet(records.size());
            entreguesA.addAndGet(records.size());
            consumer.onBatch(projecao, records.stream().map(ConsumerRecord::value).toList(), semAck);
        };
        BatchAcknowledgingMessageListener<String, String> ouvinteB = (records, ack) -> {
            entregues.addAndGet(records.size());
            entreguesB.addAndGet(records.size());
            consumer.onBatch(projecao, records.stream().map(ConsumerRecord::value).toList(), ack);
        };
        KafkaMessageListenerContainer<String, String> cA = KafkaDeTeste.container(bootstrap, grupo, topico,
                Map.of("max.poll.records", 500), AckMode.MANUAL, ouvinteA, politicaDeErroReal());
        KafkaMessageListenerContainer<String, String> cB = KafkaDeTeste.container(bootstrap, grupo, topico,
                Map.of("max.poll.records", 500), AckMode.MANUAL, ouvinteB, politicaDeErroReal());
        try {
            cA.start();
            EventsTestSupport.aguardar(Duration.ofSeconds(30),
                    () -> cA.getAssignedPartitions() != null && cA.getAssignedPartitions().size() == 3, "A receber as 3 partições");
            for (EventEnvelope e : v1) {
                publicar(topico, partitionOf(e), e);
            }
            EventsTestSupport.aguardar(Duration.ofSeconds(60), () -> entreguesA.get() > 0, "A receber mensagens");

            cB.start(); // B entra no group: rebalance com A em pleno consumo
            EventsTestSupport.aguardar(Duration.ofSeconds(60), () -> todosNaVersao(v1), "todo stream na v1");

            for (EventEnvelope e : v2) {
                publicar(topico, partitionOf(e), e);
            }
            cA.stop(); // A sai do group: outro rebalance, B fica com as 3 partições e relê o que A nunca comitou
            EventsTestSupport.aguardar(Duration.ofSeconds(60), () -> todosNaVersao(v2), "todo stream na v2");
            EventsTestSupport.aguardar(Duration.ofSeconds(60), () -> {
                try {
                    return KafkaDeTeste.grupoEmDia(bootstrap, grupo, topico);
                } catch (Exception ex) {
                    return false;
                }
            }, "B comitar os offsets até o fim das 3 partições");

            for (long k = 0; k < 9; k++) {
                long ev = BASE_EVENTO + k;
                assertEquals(2L, ativos(ev), "ativos do evento " + ev + " aplicados UMA vez (u1 +1, u2 +1-1, u3 +1)");
                assertEquals(2, checkpoint(1, ev));
                assertEquals(2, checkpoint(2, ev));
                assertEquals(1, checkpoint(3, ev));
            }
            assertTrue(entregues.get() > mensagens, "houve reentrega (entregues=" + entregues + ", mensagens=" + mensagens
                    + "): sem ela o teste não provaria nada");
            assertTrue(entreguesA.get() > 0 && entreguesB.get() > 0, "as duas réplicas consumiram (A=" + entreguesA + ", B=" + entreguesB + ")");
            assertEquals(List.of(), KafkaDeTeste.lerValores(bootstrap, topico + ".DLT"), "nada foi para o dead-letter");
        } finally {
            cA.stop();
            cB.stop();
            KafkaDeTeste.apagarTopicos(bootstrap, topico, topico + ".DLT");
        }
    }

    /** Chave = event_id (como o relay): o Kafka escolhe a partição pelo hash da chave; aqui explícita para o teste não depender dele. */
    private static int partitionOf(EventEnvelope e) {
        return (int) (e.payload().get("eventId").asLong() % 3);
    }

    private boolean todosNaVersao(List<EventEnvelope> aplicadas) {
        try {
            for (EventEnvelope e : aplicadas) {
                if (checkpoint(e.payload().get("userId").asLong(), e.payload().get("eventId").asLong()) < e.version()) {
                    return false;
                }
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    // ------------------------------------------------------------------ rehash

    @Test
    void versoesForaDeOrdemEmParticoesDiferentesSaoPreenchidasUmaVezSo() throws Exception {
        String topico = "t112-wallet-ordem-" + RODADA;
        String grupo = "t112-wallet-ordem-grupo-" + RODADA;
        KafkaDeTeste.criarTopico(bootstrap, topico, 3);
        long ev = BASE_EVENTO + 500;
        // um stream só, depois de mudar o nº de partições: v1 ficou na partição antiga (0) e as seguintes foram para a nova (2)
        EventEnvelope e1 = append(1, ev, 1, ReservationEvents.SLOT_RESERVED);   // +1
        EventEnvelope e2 = append(1, ev, 2, ReservationEvents.CONFIRMED);       // 0
        EventEnvelope e3 = append(1, ev, 3, ReservationEvents.CANCELLED);       // -1
        EventEnvelope e4 = append(1, ev, 4, ReservationEvents.SLOT_RESERVED);   // +1  => ativos final 1

        Projection projecao = new EventAvailabilityProjection();
        BatchAcknowledgingMessageListener<String, String> ouvinte = (records, ack) ->
                consumer.onBatch(projecao, records.stream().map(ConsumerRecord::value).toList(), ack);
        KafkaMessageListenerContainer<String, String> c = KafkaDeTeste.container(bootstrap, grupo, topico,
                Map.of("max.poll.records", 500), AckMode.MANUAL, ouvinte, politicaDeErroReal());
        try {
            c.start();
            EventsTestSupport.aguardar(Duration.ofSeconds(30),
                    () -> c.getAssignedPartitions() != null && c.getAssignedPartitions().size() == 3, "receber as 3 partições");
            publicar(topico, 2, e4);
            publicar(topico, 2, e3);
            publicar(topico, 2, e2);
            EventsTestSupport.aguardar(Duration.ofSeconds(60), () -> ckSeguro(1, ev) == 4,
                    "a v4 (chegou primeiro) preencher o buraco v1..v3 lendo o event_store");
            assertEquals(1L, ativos(ev), "v1..v4 aplicadas uma vez, na ordem do stream, quando só a v4 tinha chegado");

            publicar(topico, 0, e1); // a v1 chega por último, da partição antiga: já aplicada
            EventsTestSupport.aguardar(Duration.ofSeconds(60), () -> {
                try {
                    return KafkaDeTeste.grupoEmDia(bootstrap, grupo, topico);
                } catch (Exception ex) {
                    return false;
                }
            }, "os offsets chegarem ao fim");
            assertEquals(1L, ativos(ev), "a v1 tardia foi descartada pelo checkpoint (não somou de novo)");
            assertEquals(4, checkpoint(1, ev));
            assertEquals(List.of(), KafkaDeTeste.lerValores(bootstrap, topico + ".DLT"), "nada foi para o dead-letter");
        } finally {
            c.stop();
            KafkaDeTeste.apagarTopicos(bootstrap, topico, topico + ".DLT");
        }
    }

    // ------------------------------------------------------------------ 40P01

    /** {@link Projection} que, na 1ª aplicação, espera a outra réplica numa barreira e pausa {@code pausaMs} (para controlar quem espera primeiro). */
    private static final class ComBarreira implements Projection {
        private final Projection base;
        private final CyclicBarrier barreira;
        private final long pausaMs;
        private final AtomicBoolean esperou = new AtomicBoolean();

        ComBarreira(Projection base, CyclicBarrier barreira, long pausaMs) {
            this.base = base;
            this.barreira = barreira;
            this.pausaMs = pausaMs;
        }

        @Override
        public String name() {
            return base.name();
        }

        @Override
        public List<String> tables() {
            return base.tables();
        }

        @Override
        public void apply(Connection conn, EventEnvelope event, String schema) throws SQLException {
            base.apply(conn, event, schema);
            if (esperou.compareAndSet(false, true)) {
                try {
                    barreira.await(30, TimeUnit.SECONDS);
                    Thread.sleep(pausaMs);
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }
    }

    private long deadlockTimeoutMs() throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SHOW deadlock_timeout")) {
            rs.next();
            String v = rs.getString(1).trim();
            return v.endsWith("ms") ? Long.parseLong(v.replace("ms", "")) : Long.parseLong(v.replaceAll("[^0-9]", "")) * 1000;
        }
    }

    /**
     * O "Limite conhecido" do {@code onBatch}: duas réplicas aplicando, ao mesmo tempo, lotes dos MESMOS dois streams em ordens opostas
     * (o que a sobreposição de um rebalance permite) travam a linha do checkpoint uma da outra e o Postgres aborta uma com {@code 40P01}.
     * A réplica L (container real, política de erro real) é a que espera primeiro e portanto a vítima; a réplica T roda o lote oposto. O
     * {@code 40P01} sobe como {@code BatchListenerFailedException} (causa {@code SQLException}), é TRANSITÓRIO (retry, nunca DLT), o lote
     * é reentregue a partir da mensagem culpada, cada evento é aplicado uma só vez e a partição chega ao fim (não trava).
     */
    @Test
    void deadlockDeCheckpointNoRebalanceEhRepetidoNaoTravaAParticaoNemVaiAoDlt() throws Exception {
        String topico = "t112-wallet-dl-" + RODADA;
        String grupo = "t112-wallet-dl-grupo-" + RODADA;
        KafkaDeTeste.criarTopico(bootstrap, topico, 3);
        long ev1 = BASE_EVENTO + 800;
        long ev2 = BASE_EVENTO + 801;
        EventEnvelope m1 = append(1, ev1, 1, ReservationEvents.SLOT_RESERVED);
        EventEnvelope m2 = append(1, ev2, 1, ReservationEvents.SLOT_RESERVED);
        String j1 = json.writeValueAsString(m1);
        String j2 = json.writeValueAsString(m2);

        long timeout = deadlockTimeoutMs();
        CyclicBarrier barreira = new CyclicBarrier(2);
        Projection base = new EventAvailabilityProjection();
        Projection deL = new ComBarreira(base, barreira, 0);                // segue direto: começa a esperar primeiro
        Projection deT = new ComBarreira(base, barreira, timeout / 2);      // espera meio deadlock_timeout: começa a esperar depois
        List<Throwable> falhasDeL = new CopyOnWriteArrayList<>();
        AtomicInteger invocacoesDeL = new AtomicInteger();
        BatchAcknowledgingMessageListener<String, String> ouvinte = (records, ack) -> {
            invocacoesDeL.incrementAndGet();
            try {
                consumer.onBatch(deL, records.stream().map(ConsumerRecord::value).toList(), ack);
            } catch (BatchListenerFailedException ex) {
                falhasDeL.add(ex);
                throw ex;
            }
        };
        KafkaMessageListenerContainer<String, String> cL = KafkaDeTeste.container(bootstrap, grupo, topico,
                Map.of("max.poll.records", 500), AckMode.MANUAL, ouvinte, politicaDeErroReal());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // as duas mensagens já estão no tópico (partição 0) ANTES de L subir: o 1º poll traz o lote [m1, m2]
            publicar(topico, 0, m1);
            publicar(topico, 0, m2);
            Future<Optional<Throwable>> t = pool.submit(() -> {
                try {
                    consumer.onBatch(deT, List.of(j2, j1), () -> { }); // a outra réplica, em ordem oposta
                    return Optional.empty();
                } catch (RuntimeException ex) {
                    return Optional.of(ex);
                }
            });
            cL.start();

            Optional<Throwable> falhaDeT = t.get(90, TimeUnit.SECONDS);
            if (falhaDeT.isPresent()) {
                throw new AssertionError("a réplica T deveria concluir o seu lote (L é a vítima do deadlock)", falhaDeT.get());
            }
            EventsTestSupport.aguardar(Duration.ofSeconds(90), () -> !falhasDeL.isEmpty(), "L levar o 40P01");
            assertTrue(falhasDeL.stream().anyMatch(f -> temSqlState(f, "40P01")), "a causa é o deadlock 40P01: " + falhasDeL);
            EventsTestSupport.aguardar(Duration.ofSeconds(90), () -> {
                try {
                    return KafkaDeTeste.grupoEmDia(bootstrap, grupo, topico);
                } catch (Exception ex) {
                    return false;
                }
            }, "a partição chegar ao fim depois do retry (não travou)");

            assertTrue(invocacoesDeL.get() >= 2, "a mensagem culpada foi reentregue (invocações=" + invocacoesDeL + ")");
            assertEquals(1L, ativos(ev1), "evento 1 aplicado uma vez");
            assertEquals(1L, ativos(ev2), "evento 2 aplicado uma vez");
            assertEquals(1, checkpoint(1, ev1));
            assertEquals(1, checkpoint(1, ev2));
            assertEquals(List.of(), KafkaDeTeste.lerValores(bootstrap, topico + ".DLT"), "o 40P01 não mandou nada ao dead-letter");
        } finally {
            cL.stop();
            pool.shutdownNow();
            KafkaDeTeste.apagarTopicos(bootstrap, topico, topico + ".DLT");
        }
    }
}
