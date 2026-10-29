package com.gerenciador.eventos.stock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.BatchAcknowledgingMessageListener;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gerenciador.eventos.TestSupport.EventsTestSupport;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.TestSupport.KafkaDeTeste;
import com.gerenciador.eventos.reservationclient.api.EventCap;
import com.gerenciador.eventos.shared.events.EventEnvelope;
import com.gerenciador.eventos.shared.events.EventsKafkaConfig;
import com.gerenciador.eventos.stockclient.api.HoldRequest;

/**
 * {@link StockReleaseConsumer} (issue #47, D4): a devolução da vaga ao cancelar/expirar, idempotente por {@code holdId}, com Postgres e
 * Redpanda reais. A lógica do lote é chamada direto (S5, S5b, S6); o listener REAL (anotação, {@code earliest}, ack) é exercitado num
 * tópico e group de TESTE ({@code t112-*}: nunca o {@code reservation.wallet} real nem o group {@code stock}), e as duas "réplicas" do
 * rebalance são containers montados sobre um tópico de 3 partições.
 */
@SpringBootTest(properties = {"events.consumer.enabled=true", "stock.release-consumer.enabled=true",
        "spring.kafka.listener.auto-startup=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class StockReleaseConsumerTest extends StockTestSupport {

    private static final String RODADA = UUID.randomUUID().toString().substring(0, 8);
    private static final String TOPICO = "t112-stock-wallet-" + RODADA;
    private static final String GRUPO = "t112-stock-grupo-" + RODADA;

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("stock.release-consumer.topic", () -> TOPICO);
        r.add("stock.release-consumer.group", () -> GRUPO);
    }

    @Autowired private StockReleaseConsumer consumer;
    @Autowired private HoldService holdService;
    @Autowired private KafkaTemplate<String, String> template;
    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired private ObjectMapper json;
    @Value("${spring.kafka.bootstrap-servers}") private String bootstrap;

    @AfterAll
    void limpar() {
        MessageListenerContainer c = registry.getListenerContainer(StockReleaseConsumer.LISTENER_ID);
        if (c != null) {
            c.stop();
        }
        KafkaDeTeste.apagarTopicos(bootstrap, TOPICO, TOPICO + ".DLT");
    }

    // ------------------------------------------------------------------ helpers

    private UUID hold(long evento, String modo) {
        UUID h = UUID.randomUUID();
        holdService.reservar(new HoldRequest(h, novoId(), evento, modo));
        return h;
    }

    private String msg(String tipo, long eventId, UUID holdId) throws Exception {
        ObjectNode p = json.createObjectNode().put("userId", 7).put("eventId", eventId);
        if (holdId != null) {
            p.put("holdId", holdId.toString());
        }
        EventEnvelope e = new EventEnvelope(UUID.randomUUID(), "reservation.wallet", "7:" + eventId, 2, 1L, tipo, 2,
                Instant.now(), p, json.createObjectNode());
        return json.writeValueAsString(e);
    }

    // ------------------------------------------------------------------ S5: lote

    @Test
    public void loteComDuplicatasReentregaETiposMisturadosLiberaCadaHoldUmaVez() throws Exception {
        long a = novoEvento(5);
        long b = novoEvento(4);
        UUID a1 = hold(a, HoldRequest.CAS);
        UUID a2 = hold(a, HoldRequest.CAS);
        UUID a3 = hold(a, HoldRequest.CAS);
        UUID a4 = hold(a, HoldRequest.NAIVE);
        UUID b1 = hold(b, HoldRequest.CAS);
        UUID b2 = hold(b, HoldRequest.CAS);
        assertEquals(2, quant(a));
        assertEquals(2, quant(b));

        List<String> lote = List.of(
                msg("ReservationCancelled", a, a1), msg("ReservationCancelled", a, a1), // duplicata no lote
                msg("ReservationExpired", a, a2), msg("ReservationCancelled", b, b1),
                msg("SlotReserved", a, a3),                // tipo que não libera, ainda que leve holdId
                msg("ReservationConfirmed", b, b2),        // idem
                msg("ReservationCancelled", a, a4),        // hold naive: libera o hold, não devolve vaga
                msg("ReservationExpired", b, b1));         // o mesmo hold por outro tipo

        assertEquals(4, consumer.liberar(lote), "a1, a2, b1 e a4, cada um UMA vez");
        assertEquals(4, quant(a), "a1 e a2 devolvem (a4 não tinha débito)");
        assertEquals(3, quant(b));
        assertEquals("held", statusDoHold(a3));
        assertEquals("held", statusDoHold(b2));
        assertEquals("released", statusDoHold(a4));

        assertEquals(0, consumer.liberar(lote), "reentrega do mesmo lote: no-op");
        assertEquals(4, quant(a));
        assertEquals(3, quant(b));
        assertInvariante(a);
        assertInvariante(b);
    }

    // ------------------------------------------------------------------ S5b: o que não pode travar a partição

    @Test
    public void mensagensSemHoldIdOuInvalidasNaoTravamNemLiberamNada() throws Exception {
        long ev = novoEvento(3);
        UUID h = hold(ev, HoldRequest.CAS);
        assertEquals(2, quant(ev));

        List<String> lote = new ArrayList<>(List.of(
                "{nao e json", "{}",
                msg("ReservationCancelled", ev, null),                      // v1, anterior à migração
                msg("ReservationExpired", ev, null)));
        ObjectNode semUuid = (ObjectNode) json.readTree(msg("ReservationCancelled", ev, h));
        ((ObjectNode) semUuid.get("payload")).put("holdId", "isto-nao-e-uuid");
        lote.add(semUuid.toString());

        assertEquals(0, consumer.liberar(lote), "nada para liberar e nenhuma exceção");
        assertEquals(2, quant(ev));
        assertEquals("held", statusDoHold(h));

        lote.add(msg("ReservationCancelled", ev, h)); // no meio do lixo, a válida é liberada
        assertEquals(1, consumer.liberar(lote));
        assertEquals(3, quant(ev));
    }

    /**
     * O listener REAL num group NOVO com {@code earliest}. O container não sobe com o contexto ({@code auto-startup=false}): o
     * cancelamento é publicado ANTES de o group existir e só então o container dá o 1º start, sem offset comitado, então vale a política
     * de reset. (Parar e religar não serve: ao revogar as partições o Spring comita a posição, e o reset deixa de valer.) Com
     * {@code latest} a mensagem seria pulada e a vaga ficaria presa.
     */
    @Test
    public void groupNovoComEarliestLiberaCancelamentoPublicadoAntesDoPrimeiroPoll() throws Exception {
        MessageListenerContainer c = registry.getListenerContainer(StockReleaseConsumer.LISTENER_ID);
        assertNotNull(c, "o listener do StockReleaseConsumer foi criado");
        assertFalse(c.isRunning(), "ainda não subiu: o group não existe");
        long ev = novoEvento(2);
        UUID h = hold(ev, HoldRequest.CAS);
        assertEquals(1, quant(ev));

        template.send(TOPICO, String.valueOf(ev), msg("ReservationCancelled", ev, h)).get(10, TimeUnit.SECONDS);
        c.start();

        EventsTestSupport.aguardar(Duration.ofSeconds(60), () -> quant(ev) == 2, "o listener liberar o hold publicado antes do poll");
        assertEquals("released", statusDoHold(h));
        EventsTestSupport.aguardar(Duration.ofSeconds(60), () -> {
            try {
                return KafkaDeTeste.grupoEmDia(bootstrap, GRUPO, TOPICO);
            } catch (Exception ex) {
                return false;
            }
        }, "o offset ser comitado depois do commit do banco (ack)");
        assertEquals(List.of(), KafkaDeTeste.lerValores(bootstrap, TOPICO + ".DLT"));
    }

    // ------------------------------------------------------------------ S6: conversão ilimitado -> limitado

    @Test
    public void holdDeAntesDaConversaoIlimitadoLimitadoDevolveAVagaPeloConsumer() throws Exception {
        long ev = novoEvento(EventCap.UNLIMITED);
        UUID h = hold(ev, HoldRequest.CAS);
        assertEquals(Boolean.FALSE, debitado(h), "evento ilimitado: hold sem débito");

        eventCapRepository.updatePolicy(ev, 10, null);
        assertEquals(9, quant(ev), "10 - 1 hold ativo");
        assertEquals(Boolean.TRUE, debitado(h), "a conversão marcou o débito");

        assertEquals(1, consumer.liberar(List.of(msg("ReservationCancelled", ev, h))));
        assertEquals(10, quant(ev));
        assertInvariante(ev);
    }

    // ------------------------------------------------------------------ S5: rebalance com duas instâncias

    /**
     * A e B no mesmo group (3 partições de TESTE): B entra no meio do fluxo e A sai, e os offsets de A nunca são comitados (A "morre"
     * depois do commit do banco e antes do commit do offset: a janela at-least-once do rebalance), então B reentrega o que A já
     * liberou. Cada hold é liberado UMA vez (cada evento volta exatamente à capacidade, sem passar dela) e nada vai ao dead-letter.
     */
    @Test
    public void rebalanceReentregaMasCadaHoldELiberadoUmaVez() throws Exception {
        String topico = "t112-stock-rebalance-" + RODADA;
        String grupo = "t112-stock-rebalance-grupo-" + RODADA;
        KafkaDeTeste.criarTopico(bootstrap, topico, 3);
        int eventos = 6;
        long[] ev = new long[eventos];
        UUID[][] holds = new UUID[eventos][3];
        for (int k = 0; k < eventos; k++) {
            ev[k] = novoEvento(3);
            for (int j = 0; j < 3; j++) {
                holds[k][j] = hold(ev[k], HoldRequest.CAS);
            }
            assertEquals(0, quant(ev[k]));
        }
        AtomicInteger entregues = new AtomicInteger();
        AtomicInteger entreguesA = new AtomicInteger();
        AtomicInteger entreguesB = new AtomicInteger();
        Acknowledgment semAck = () -> { };
        BatchAcknowledgingMessageListener<String, String> ouvinteA = (records, ack) -> {
            entregues.addAndGet(records.size());
            entreguesA.addAndGet(records.size());
            consumer.liberar(records.stream().map(ConsumerRecord::value).toList());
            semAck.acknowledge();
        };
        BatchAcknowledgingMessageListener<String, String> ouvinteB = (records, ack) -> {
            entregues.addAndGet(records.size());
            entreguesB.addAndGet(records.size());
            consumer.liberar(records.stream().map(ConsumerRecord::value).toList());
            ack.acknowledge();
        };
        @SuppressWarnings("unchecked")
        KafkaOperations<Object, Object> ops = (KafkaOperations<Object, Object>) (KafkaOperations<?, ?>) template;
        KafkaMessageListenerContainer<String, String> cA = KafkaDeTeste.container(bootstrap, grupo, topico,
                Map.of("max.poll.records", 500), AckMode.MANUAL, ouvinteA, new EventsKafkaConfig().eventsErrorHandler(ops, 200, 2, 10));
        KafkaMessageListenerContainer<String, String> cB = KafkaDeTeste.container(bootstrap, grupo, topico,
                Map.of("max.poll.records", 500), AckMode.MANUAL, ouvinteB, new EventsKafkaConfig().eventsErrorHandler(ops, 200, 2, 10));
        int mensagens = 0;
        try {
            cA.start();
            EventsTestSupport.aguardar(Duration.ofSeconds(30),
                    () -> cA.getAssignedPartitions() != null && cA.getAssignedPartitions().size() == 3, "A receber as 3 partições");
            for (int k = 0; k < eventos; k++) { // fase 1: h0 e h1 de cada evento, cada mensagem duas vezes
                for (int j = 0; j < 2; j++) {
                    for (int rep = 0; rep < 2; rep++) {
                        publicar(topico, ev[k], msg("ReservationCancelled", ev[k], holds[k][j]));
                        mensagens++;
                    }
                }
            }
            EventsTestSupport.aguardar(Duration.ofSeconds(60), () -> entreguesA.get() > 0, "A receber mensagens");
            cB.start(); // B entra no group: rebalance com A em pleno consumo
            EventsTestSupport.aguardar(Duration.ofSeconds(60), () -> liberados(holds, 2) == eventos * 2, "h0 e h1 de todo evento liberados");
            for (int k = 0; k < eventos; k++) { // fase 2: h2 por expiração
                publicar(topico, ev[k], msg("ReservationExpired", ev[k], holds[k][2]));
                mensagens++;
            }
            cA.stop(); // A sai do group: B fica com as 3 partições e relê o que A nunca comitou
            EventsTestSupport.aguardar(Duration.ofSeconds(60), () -> liberados(holds, 3) == eventos * 3, "todos os holds liberados");
            EventsTestSupport.aguardar(Duration.ofSeconds(60), () -> {
                try {
                    return KafkaDeTeste.grupoEmDia(bootstrap, grupo, topico);
                } catch (Exception ex) {
                    return false;
                }
            }, "B comitar os offsets até o fim das 3 partições");

            for (int k = 0; k < eventos; k++) {
                assertEquals(3, quant(ev[k]), "evento " + ev[k] + ": cada hold devolveu UMA vaga (nem a mais: sem passar da capacidade)");
                assertInvariante(ev[k]);
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

    /** Chave = event_id (como o relay), na partição event_id % 3: explícita, para o teste não depender do hash do Kafka. */
    private void publicar(String topico, long eventId, String valor) throws Exception {
        template.send(new ProducerRecord<>(topico, (int) (eventId % 3), String.valueOf(eventId), valor)).get(10, TimeUnit.SECONDS);
    }

    /** Quantos dos primeiros {@code porEvento} holds de cada evento já estão {@code released}. */
    private int liberados(UUID[][] holds, int porEvento) {
        int n = 0;
        try {
            for (UUID[] doEvento : holds) {
                for (int j = 0; j < porEvento; j++) {
                    if ("released".equals(statusDoHold(doEvento[j]))) {
                        n++;
                    }
                }
            }
        } catch (Exception ex) {
            return -1;
        }
        return n;
    }
}
