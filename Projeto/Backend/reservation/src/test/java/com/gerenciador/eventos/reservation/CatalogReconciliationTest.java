package com.gerenciador.eventos.reservation;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.reservationclient.api.EventCap;
import com.gerenciador.eventos.shared.events.EventEnvelope;
import com.gerenciador.eventos.shared.events.InvalidEventMessageException;

/**
 * Reconciliação do {@code eventcap} pelo {@code EventCreated} do tópico {@code catalog.event} (issue #33, decisão 3): o
 * catalog cria a capacidade por chamada síncrona depois de comitar o evento; se ela não chegar, o reservation a cria sozinho
 * a partir do evento. A criação é idempotente por {@code event_id}: quem chega primeiro vence e o resto é no-op.
 *
 * <p>Liga a reconciliação SÓ aqui (nos outros contextos ela fica desligada: um {@code EventCreated} de outro teste não
 * pode criar {@code eventcap} para ids que eles controlam), com um group exclusivo desta execução e ids altíssimos. O
 * listener lê com {@code auto.offset.reset=latest}: o teste reenvia a mensagem (idempotente, e de brinde prova a
 * idempotência) até o {@code eventcap} aparecer, em vez de adivinhar quando a atribuição de partição termina.</p>
 */
@SpringBootTest(properties = {
        "events.consumer.enabled=true", "reservation.catalog-reconciliation.enabled=true",
        "reservation.catalog-reconciliation.group=reservation-catalog-test-${random.uuid}"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class CatalogReconciliationTest extends ReservationTestSupport {

    private static final Duration ESPERA = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired private CatalogReconciliationConsumer consumer;

    @Test
    public void eventCreatedSemEventcapCriaOEventcapEARepeticaoNaoAltera() throws Exception {
        long id = 2_100_000_001L;
        String msg = eventCreated(id, 6, "2030-01-01T10:00:00");

        reenviarAte(msg, () -> eventCapRepository.findByEventId(id) != null);

        EventCap cap = eventCapRepository.findByEventId(id);
        assertEquals(6, cap.getCapacity());
        assertEquals(6, cap.getQuant());
        assertEquals(java.time.LocalDateTime.parse("2030-01-01T10:00:00"), cap.getBuyTimeLimit());

        // reentrega / repetição: no-op (o consumer não precisa de checkpoint)
        eventCapRepository.updatePolicy(id, 9, null);
        kafka.send("catalog.event", String.valueOf(id), msg).get(10, TimeUnit.SECONDS);
        Thread.sleep(1500);
        assertEquals(9, eventCapRepository.findByEventId(id).getCapacity(), "a repetição não sobrescreve");
    }

    @Test
    public void eventcapCriadoPelaChamadaSincronaNaoEhSobrescritoPeloEventCreated() throws Exception {
        long id = 2_100_000_002L;
        eventCapRepository.create(new EventCap(id, 4, 4, null)); // a chamada síncrona chegou primeiro

        assertFalse(consumer.reconciliar(eventCreated(id, 50, "2030-01-01T10:00:00")), "já existia: não criou");
        assertEquals(4, eventCapRepository.findByEventId(id).getCapacity());
    }

    @Test
    public void tiposQueNaoSaoEventCreatedSaoIgnoradosEPayloadInvalidoEhRejeitado() throws Exception {
        long id = 2_100_000_003L;
        ObjectNode atualizado = envelope("EventUpdated", JSON.createObjectNode().put("eventId", id));
        assertFalse(consumer.reconciliar(atualizado.toString()));
        assertNull(eventCapRepository.findByEventId(id));

        assertThrows(InvalidEventMessageException.class, () -> consumer.reconciliar("{nao e json"));
        assertThrows(InvalidEventMessageException.class,
                () -> consumer.reconciliar(envelope("EventCreated", JSON.createObjectNode().put("eventId", id)).toString()));
        assertNull(eventCapRepository.findByEventId(id));
    }

    @Test
    public void eventoIlimitadoEhReconciliadoComoIlimitado() throws Exception {
        long id = 2_100_000_004L;
        assertTrue(consumer.reconciliar(eventCreated(id, -1, null)));
        EventCap cap = eventCapRepository.findByEventId(id);
        assertTrue(cap.isUnlimited());
        assertEquals(-1, cap.getQuant());
        assertNull(cap.getBuyTimeLimit());
    }

    /** Issue #58: capacity 0 (ou < -1) viola o CHECK; sem tratar, seria SQLException retentada sem limite e travaria a partição. */
    @Test
    public void capacityInvalidaEhPayloadInvalidoENaoCriaEventcap() throws Exception {
        long id = 2_100_000_005L;
        assertThrows(InvalidEventMessageException.class, () -> consumer.reconciliar(eventCreated(id, 0, null)));
        assertThrows(InvalidEventMessageException.class, () -> consumer.reconciliar(eventCreated(id, -2, null)));
        assertNull(eventCapRepository.findByEventId(id));
    }

    /**
     * Réplicas do reservation (autoescala, issue #112): no rebalance a MESMA mensagem pode chegar a duas réplicas ao mesmo tempo (a que
     * perdeu a partição ainda processa a sua, a que ganhou a reentrega). A criação é idempotente por {@code event_id}: quatro execuções
     * simultâneas criam o {@code eventcap} UMA vez e o resto é no-op, sem erro. Este consumer não tem checkpoint; a idempotência está
     * toda no {@code ON CONFLICT DO NOTHING} de {@code EventCapRepository.create}.
     */
    @Test
    public void mesmaMensagemEmQuatroReplicasAoMesmoTempoCriaOEventcapUmaSoVez() throws Exception {
        long id = 2_100_000_006L;
        String msg = eventCreated(id, 8, "2030-01-01T10:00:00");

        java.util.List<Boolean> criou = Concorrencia.emParalelo(4, () -> consumer.reconciliar(msg));

        assertEquals(1, criou.stream().filter(b -> b).count(), "exatamente uma réplica criou: " + criou);
        EventCap cap = eventCapRepository.findByEventId(id);
        assertEquals(8, cap.getCapacity());
        assertEquals(8, cap.getQuant());
    }

    // ===================== helpers =====================

    private void reenviarAte(String mensagem, java.util.function.BooleanSupplier condicao) throws Exception {
        assertNotNull(registry.getListenerContainer(CatalogReconciliationConsumer.LISTENER_ID),
                "o listener da reconciliação foi criado");
        long fim = System.nanoTime() + ESPERA.toNanos();
        while (!condicao.getAsBoolean()) {
            if (System.nanoTime() > fim) {
                throw new AssertionError("Timeout esperando o eventcap ser reconciliado pelo tópico");
            }
            kafka.send("catalog.event", "reenvio", mensagem).get(10, TimeUnit.SECONDS);
            for (int i = 0; i < 40 && !condicao.getAsBoolean(); i++) { // até 2 s antes de reenviar
                Thread.sleep(50);
            }
        }
    }

    private static String eventCreated(long id, int capacity, String buyTimeLimit) {
        ObjectNode p = JSON.createObjectNode().put("eventId", id).put("creatorId", 1).put("eventName", "n" + id)
                .put("capacity", capacity);
        if (buyTimeLimit != null) {
            p.put("buyTimeLimit", buyTimeLimit);
        }
        return envelope("EventCreated", p).toString();
    }

    private static ObjectNode envelope(String tipo, ObjectNode payload) {
        EventEnvelope e = new EventEnvelope(UUID.randomUUID(), "catalog.event", payload.get("eventId").asText(), 1, 1L,
                tipo, 1, Instant.now(), payload, JSON.createObjectNode());
        return JSON.valueToTree(e);
    }
}
