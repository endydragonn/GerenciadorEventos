package com.gerenciador.eventos.notification;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.TestSupport.KafkaDeTeste;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventEnvelope;
import com.gerenciador.eventos.shared.events.EventsKafkaConfig;
import com.gerenciador.eventos.shared.events.NotificationEvents;

/**
 * Réplicas do {@code notification} (autoescala, issue #112): nenhum e-mail sai em dobro e nenhum se perde quando o group muda de membros no
 * meio do fluxo. Postgres e Redpanda reais; o remetente é um fake (nenhum SMTP, nenhum e-mail real): conta os envios por destinatário, e o
 * destinatário carrega o {@code eventId}, então "exatamente 1 envio por eventId" é conferido direto.
 *
 * <p>Tópico e group de TESTE (prefixo {@code t112-}) criados aqui com 3 partições, que é o que a #112 propõe para escalar o consumer (o
 * tópico real do compose tem 1; a ampliação em si NÃO é feita por este teste nem pelo código). Duas "réplicas" = dois
 * {@link NotificationConsumer} no mesmo group, cada um com o seu container e o seu remetente, com a política de erro REAL
 * ({@code EventsKafkaConfig}).</p>
 *
 * <p>Mesmas propriedades e {@code @MockitoBean} do {@link NotificationConsumerTest}: o contexto é o mesmo (em cache) e a credencial SMTP real
 * do {@code --env-file} nunca chega a um remetente de verdade.</p>
 */
@SpringBootTest(classes = NotificationApplication.class, properties = {
        "spring.kafka.consumer.group-id=notification-test",
        "events.consumer.retry.interval-ms=200", "events.consumer.retry.max-attempts=2"})
public class NotificationRebalanceTest {

    private static final String BOOTSTRAP = System.getenv().getOrDefault("SPRING_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
    private static final String RODADA = UUID.randomUUID().toString().substring(0, 8);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Autowired private KafkaTemplate<String, String> template;
    @Autowired private DatabaseConnection db;
    @Autowired private ObjectMapper json;
    @MockitoBean private JavaMailSender mailSender;

    // ------------------------------------------------------------------ helpers

    private static EventEnvelope evento() {
        UUID id = UUID.randomUUID();
        var payload = JSON.createObjectNode().put(NotificationEvents.EMAIL, id + "@example.com")
                .put(NotificationEvents.USER_NAME, "Teste").put(NotificationEvents.CODE, "123456")
                .put(NotificationEvents.EXPIRATION_MINUTES, 5)
                .put(NotificationEvents.EXPIRES_AT, Instant.now().plusSeconds(300).toString());
        return new EventEnvelope(id, NotificationEvents.STREAM_TYPE, UUID.randomUUID().toString(), 1, 0L,
                NotificationEvents.VERIFICATION_CODE_REQUESTED, 1, Instant.now(), payload, JSON.createObjectNode());
    }

    /** Remetente fake que conta por destinatário; {@code aoEnviar} roda antes (para o envio demorar). */
    private static JavaMailSender remetente(Map<String, AtomicInteger> envios, Runnable aoEnviar) {
        JavaMailSender s = mock(JavaMailSender.class);
        doAnswer(inv -> {
            aoEnviar.run();
            envios.computeIfAbsent(((SimpleMailMessage) inv.getArgument(0)).getTo()[0], k -> new AtomicInteger()).incrementAndGet();
            return null;
        }).when(s).send(any(SimpleMailMessage.class));
        return s;
    }

    private NotificationConsumer replica(JavaMailSender s) {
        return new NotificationConsumer(db, json, new EmailNotificationService(s, "teste@example.com"));
    }

    @SuppressWarnings("unchecked")
    private DefaultErrorHandler politicaDeErroReal() {
        return new EventsKafkaConfig().eventsErrorHandler((KafkaOperations<Object, Object>) (KafkaOperations<?, ?>) template,
                200, 2, 10);
    }

    private AcknowledgingMessageListener<String, String> ouvinte(NotificationConsumer c, Map<String, AtomicInteger> entregas,
            AtomicInteger entreguesAEsta) {
        return (ConsumerRecord<String, String> rec, Acknowledgment ack) -> {
            try {
                entregas.computeIfAbsent(json.readTree(rec.value()).get("eventId").asText(), k -> new AtomicInteger()).incrementAndGet();
                entreguesAEsta.incrementAndGet();
                c.ouvir(rec.value(), ack);
            } catch (SQLException | java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
        };
    }

    private int processados(List<EventEnvelope> eventos) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM notification.processed_event WHERE event_id = ANY(?)")) {
            Array ids = c.createArrayOf("uuid", eventos.stream().map(EventEnvelope::eventId).toArray());
            ps.setArray(1, ids);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @FunctionalInterface
    private interface Condicao {
        boolean ok() throws Exception;
    }

    private static void esperar(Duration limite, Condicao condicao, String oQue) throws Exception {
        long fim = System.nanoTime() + limite.toNanos();
        while (!condicao.ok()) {
            if (System.nanoTime() > fim) {
                throw new AssertionError("Timeout (" + limite + ") esperando: " + oQue);
            }
            Thread.sleep(100);
        }
    }

    private void publicar(String topico, List<EventEnvelope> lote) throws Exception {
        for (EventEnvelope e : lote) {
            template.send(topico, e.streamId(), json.writeValueAsString(e)).get(10, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------ testes

    /**
     * O fluxo inteiro com rebalance de verdade: A sobe sozinho com as 3 partições; B entra no group no meio; A fica preso num envio mais
     * tempo que o {@code max.poll.interval.ms} (6 s) e é expulso do group com a mensagem sem commit (o rebalance que o aceite pede); depois A
     * sai (stop) com mensagens ainda para consumir. Cada eventId sai UMA vez (o {@code processed_event} segura a reentrega, inclusive a que
     * chega enquanto A ainda tem a transação aberta), todos saem e nenhum vai ao DLT. O teste exige que tenha havido reentrega (se
     * nenhuma mensagem chegasse duas vezes, "1 envio" não provaria nada).
     */
    @Test
    void rebalanceNoMeioDoFluxoMandaCadaEmailUmaSoVezESemPerderNenhum() throws Exception {
        String topico = "t112-notif-" + RODADA;
        String grupo = "t112-notif-grupo-" + RODADA;
        KafkaDeTeste.criarTopico(BOOTSTRAP, topico, 3);
        Map<String, AtomicInteger> envios = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> entregas = new ConcurrentHashMap<>();
        AtomicInteger entreguesAA = new AtomicInteger();
        AtomicInteger entreguesAB = new AtomicInteger();
        AtomicBoolean aDormindo = new AtomicBoolean();
        AtomicBoolean jaDormiu = new AtomicBoolean();
        NotificationConsumer a = replica(remetente(envios, () -> {
            if (jaDormiu.compareAndSet(false, true)) { // o 1º envio de A passa do max.poll.interval.ms: A é expulso do group
                aDormindo.set(true);
                try {
                    Thread.sleep(9_000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }));
        NotificationConsumer b = replica(remetente(envios, () -> { }));
        KafkaMessageListenerContainer<String, String> cA = KafkaDeTeste.container(BOOTSTRAP, grupo, topico,
                Map.of("max.poll.interval.ms", 6_000, "max.poll.records", 10, "session.timeout.ms", 6_000),
                AckMode.MANUAL_IMMEDIATE, ouvinte(a, entregas, entreguesAA), politicaDeErroReal());
        KafkaMessageListenerContainer<String, String> cB = KafkaDeTeste.container(BOOTSTRAP, grupo, topico,
                Map.of("max.poll.records", 10), AckMode.MANUAL_IMMEDIATE, ouvinte(b, entregas, entreguesAB), politicaDeErroReal());
        List<EventEnvelope> todos = new ArrayList<>();
        try {
            cA.start();
            esperar(Duration.ofSeconds(30), () -> cA.getAssignedPartitions() != null && cA.getAssignedPartitions().size() == 3,
                    "A receber as 3 partições");

            List<EventEnvelope> lote1 = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                lote1.add(evento());
            }
            todos.addAll(lote1);
            publicar(topico, lote1);
            esperar(Duration.ofSeconds(30), aDormindo::get, "A começar o envio lento");

            cB.start(); // B entra no group com A no meio do envio: rebalance
            esperar(Duration.ofSeconds(90), () -> processados(lote1) == lote1.size(), "o lote 1 inteiro processado");

            List<EventEnvelope> lote2 = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                lote2.add(evento());
            }
            todos.addAll(lote2);
            publicar(topico, lote2);
            cA.stop(); // A sai do group com mensagens ainda por consumir: outro rebalance, B fica com tudo
            esperar(Duration.ofSeconds(90), () -> processados(lote2) == lote2.size(), "o lote 2 inteiro processado");
            esperar(Duration.ofSeconds(60), () -> KafkaDeTeste.grupoEmDia(BOOTSTRAP, grupo, topico),
                    "os offsets do group chegarem ao fim das 3 partições");

            for (EventEnvelope e : todos) {
                AtomicInteger n = envios.get(e.eventId() + "@example.com");
                assertEquals(1, n == null ? 0 : n.get(), "exatamente um envio para o eventId " + e.eventId());
            }
            assertEquals(todos.size(), envios.size(), "nenhum envio a destinatário fora do teste");
            assertEquals(todos.size(), processados(todos));
            int totalEntregas = entregas.values().stream().mapToInt(AtomicInteger::get).sum();
            assertTrue(totalEntregas > todos.size(), "houve reentrega no rebalance (entregas=" + totalEntregas
                    + ", eventos=" + todos.size() + "): sem ela o teste não provaria nada");
            assertTrue(entreguesAA.get() > 0 && entreguesAB.get() > 0,
                    "as duas réplicas consumiram (A=" + entreguesAA + ", B=" + entreguesAB + ")");
            assertEquals(List.of(), KafkaDeTeste.lerValores(BOOTSTRAP, topico + ".DLT"), "nada foi para o dead-letter");
        } finally {
            cA.stop();
            cB.stop();
            KafkaDeTeste.apagarTopicos(BOOTSTRAP, topico, topico + ".DLT");
        }
    }

    /**
     * A sobreposição determinística: a réplica A está no meio do envio (transação aberta, {@code processed_event} inserido e ainda sem commit)
     * quando a réplica B recebe o MESMO evento (a mensagem que o rebalance reentrega). O {@code INSERT ... ON CONFLICT DO NOTHING} de B
     * espera o commit de A e depois vê o conflito: B não envia. Se o rowcount do registro não fosse conferido, B mandaria o 2º e-mail.
     */
    @Test
    void reentregaAOutraReplicaEnquantoAPrimeiraAindaEstaEnviandoNaoMandaOSegundoEmail() throws Exception {
        EventEnvelope e = evento();
        Map<String, AtomicInteger> enviosA = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> enviosB = new ConcurrentHashMap<>();
        CountDownLatch aEnviando = new CountDownLatch(1);
        CountDownLatch liberaA = new CountDownLatch(1);
        NotificationConsumer a = replica(remetente(enviosA, () -> {
            aEnviando.countDown();
            try {
                liberaA.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }));
        NotificationConsumer b = replica(remetente(enviosB, () -> { }));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> fa = pool.submit(() -> a.processar(e));
            assertTrue(aEnviando.await(30, TimeUnit.SECONDS), "A chegou ao envio, com a transação aberta");
            Future<Boolean> fb = pool.submit(() -> b.processar(e));
            Thread.sleep(1_500);
            assertFalse(fb.isDone(), "B espera o commit de A no registro do evento (não passa direto para o envio)");
            liberaA.countDown();

            assertTrue(fa.get(30, TimeUnit.SECONDS), "A enviou");
            assertFalse(fb.get(30, TimeUnit.SECONDS), "B viu o evento já registrado e descartou");
        } finally {
            liberaA.countDown();
            pool.shutdownNow();
        }
        assertEquals(1, enviosA.get(e.eventId() + "@example.com").get());
        assertTrue(enviosB.isEmpty(), "B não enviou nada");
        assertEquals(1, processados(List.of(e)));
    }
}
