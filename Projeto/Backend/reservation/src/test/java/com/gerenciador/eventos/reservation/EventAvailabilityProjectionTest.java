package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.BatchMessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;

import com.gerenciador.eventos.TestSupport.EventsTestSupport;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.reservation.EventAvailabilityConsumer;
import com.gerenciador.eventos.reservation.EventAvailabilityProjection;
import com.gerenciador.eventos.reservation.EventAvailabilityRepository;
import com.gerenciador.eventos.reservation.EventWalletService;
import com.gerenciador.eventos.reservation.ReservationEvents;
import com.gerenciador.eventos.reservationclient.ReservationClient;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventEnvelope;
import com.gerenciador.eventos.shared.events.EventStore;
import com.gerenciador.eventos.shared.events.IdempotentConsumer;

/**
 * Ponta a ponta da projeção assíncrona de disponibilidade (issue #29): reserva -> append {@code SlotReserved} na
 * transação do CAS -> relay -> tópico {@code reservation.wallet} -> consumer idempotente -> {@code ativos} ->
 * {@code findAvailable} coerente. Relay e consumer ligados por flag.
 *
 * <p>{@code @DirtiesContext}: o relay de 50 ms e o listener não podem sobrar no cache de contextos e roubar o outbox
 * dos outros testes. O listener deste contexto pode não receber a partição do tópico (1 partição, mesmo group de
 * outros contextos em cache): quem aplica o evento é indiferente, todos escrevem no mesmo banco, então os testes
 * esperam o EFEITO no banco (nunca a atribuição de partição deste contexto).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "events.relay.enabled=true", "events.relay.interval-ms=50", "events.consumer.enabled=true"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class EventAvailabilityProjectionTest extends ReservationTestSupport {

    private static final Duration ESPERA = Duration.ofSeconds(60);
    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventAvailabilityRepository eventAvailabilityRepository;
    @Autowired private IdempotentConsumer idempotentConsumer;
    @Autowired private EventStore eventStore;
    @Autowired private DatabaseConnection databaseConnection;
    @Autowired private KafkaListenerEndpointRegistry registry;
    @LocalServerPort private int porta;
    @Value("${internal.api.token}") private String tokenInterno;

    @Test
    public void disponibilidadeEhCapacidadeMenosReservasAtivasDepoisDoConsumer() throws Exception {
        Usuario creator = novoUsuario("proj-cap-creator@example.com", "proj-fone-01");
        Evento e = novoEvento(creator.getId(), "Proj Cap5", 5);
        assertEquals(5, eventWalletService.disponibilidade(e.getEvent_id()), "sem reservas: a capacidade inteira");

        for (int i = 0; i < 2; i++) {
            Usuario u = novoUsuario("proj-cap-u" + i + "@example.com", "proj-fone-1" + i);
            eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
            eventWalletService.confirm(u.getId(), e.getEvent_id());
        }

        aguardarDisponivel(e.getEvent_id(), 3); // capacity 5 - 2 reservas ativas
    }

    @Test
    public void eventoIlimitadoFicaMenosUmNaLeitura() throws Exception {
        Usuario creator = novoUsuario("proj-ilim-creator@example.com", "proj-fone-02");
        Evento e = novoEvento(creator.getId(), "Proj Ilimitado", -1);
        Usuario u = novoUsuario("proj-ilim-u@example.com", "proj-fone-20");

        assertEquals(-1, eventWalletService.disponibilidade(e.getEvent_id()));
        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        aguardarAtivos(e.getEvent_id(), 1);
        assertEquals(-1, eventWalletService.disponibilidade(e.getEvent_id()),
                "capacity -1 => -1, com ou sem ativos na projeção");
    }

    @Test
    public void cancelamentoEExpiracaoDevolvemDisponibilidadePeloConsumer() throws Exception {
        Usuario creator = novoUsuario("proj-cancel-creator@example.com", "proj-fone-03");
        Evento e = novoEvento(creator.getId(), "Proj Cancel", 2);
        Usuario u = novoUsuario("proj-cancel-u@example.com", "proj-fone-30");

        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        eventWalletService.confirm(u.getId(), e.getEvent_id());
        aguardarDisponivel(e.getEvent_id(), 1);

        eventWalletService.removeLink(u.getId(), e.getEvent_id());
        aguardarDisponivel(e.getEvent_id(), 2);

        // reserva nova do mesmo par continua o mesmo stream (version segue) e a projeção conta +1 de novo
        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        aguardarDisponivel(e.getEvent_id(), 1);
    }

    /**
     * Lag observável (ADR, seção 4): a linha da projeção guarda {@code last_occurred_at} (o {@code occurred_at} do
     * último evento aplicado, o mesmo do {@code event_store}) e {@code applied_at} (commit do consumer, depois do
     * evento), e os dois andam a cada evento aplicado, inclusive {@code ReservationConfirmed} (delta 0).
     */
    @Test
    public void linhaDaProjecaoGuardaOccurredAtEAppliedAtDoUltimoEventoAplicado() throws Exception {
        Usuario creator = novoUsuario("proj-lag-creator@example.com", "proj-fone-04");
        Evento e = novoEvento(creator.getId(), "Proj Lag", 3);
        Usuario u = novoUsuario("proj-lag-u@example.com", "proj-fone-40");

        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        aguardarAtivos(e.getEvent_id(), 1);
        Instant occurred1 = ultimoOccurredNoLog(e.getEvent_id());
        Linha l1 = lerLinha(e.getEvent_id());
        assertNotNull(l1);
        assertEquals(occurred1, l1.lastOccurredAt(), "last_occurred_at = occurredAt do SlotReserved");
        assertFalse(l1.appliedAt().isBefore(l1.lastOccurredAt()), "applied_at vem depois do evento");
        assertTrue(Duration.between(l1.lastOccurredAt(), l1.appliedAt()).toSeconds() < 30, "lag de segundos, não de dias");

        eventWalletService.confirm(u.getId(), e.getEvent_id());
        EventsTestSupport.aguardar(ESPERA, () -> {
            try {
                return lerLinha(e.getEvent_id()).lastOccurredAt().isAfter(l1.lastOccurredAt());
            } catch (Exception ex) {
                return false;
            }
        }, "o Confirmed (delta 0) também atualizar last_occurred_at/applied_at");
        Linha l2 = lerLinha(e.getEvent_id());
        assertEquals(ultimoOccurredNoLog(e.getEvent_id()), l2.lastOccurredAt());
        assertFalse(l2.appliedAt().isBefore(l1.appliedAt()));
        assertEquals(1, l2.ativos(), "Confirmed não muda ativos");
    }

    /** Reentrega (at-least-once do relay) do mesmo evento não conta duas vezes: o checkpoint por stream barra. */
    @Test
    public void reenvioDeEventoJaAplicadoNaoContaDeNovo() throws Exception {
        Usuario creator = novoUsuario("proj-dup-creator@example.com", "proj-fone-05");
        Evento e = novoEvento(creator.getId(), "Proj Dup", 4);
        Usuario u = novoUsuario("proj-dup-u@example.com", "proj-fone-50");

        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        aguardarAtivos(e.getEvent_id(), 1);

        List<EventEnvelope> log;
        try (Connection conn = databaseConnection.getConnection()) {
            log = eventStore.readStream(conn, ReservationEvents.STREAM_TYPE,
                    ReservationEvents.streamId(u.getId(), e.getEvent_id()), 1);
        }
        assertEquals(1, log.size());
        EventAvailabilityProjection projecao = new EventAvailabilityProjection();
        assertFalse(idempotentConsumer.process(projecao, log.get(0)), "já aplicado: descartado");
        assertEquals(3, eventWalletService.disponibilidade(e.getEvent_id()));
    }

    /**
     * Ponta a ponta do braço de controle (#31), agora COM o salto de rede (#33): o {@link ReservationClient} real faz o
     * {@code addLink} naive por HTTP no serviço real (GET da disponibilidade + POST da reserva sem CAS) -> append
     * {@code SlotReserved} -> relay -> tópico -> consumer -> {@code ativos} -> a próxima leitura. O naive nunca toca
     * {@code eventcap.quant} (continua na capacidade), então a disponibilidade que cai só pode vir da projeção real; e,
     * com ela em 0, o próximo {@code addLink} é barrado ("Evento lotado") por essa leitura.
     */
    @Test
    public void addLinkNaiveRefleteNaDisponibilidadePelaProjecaoReal() throws Exception {
        Evento e = novoEvento(null, "Proj Naive", 2);
        Usuario u1 = novoUsuario("u1");
        Usuario u2 = novoUsuario("u2");
        Usuario u3 = novoUsuario("u3");

        ReservationClient cliente = new ReservationClient("http://localhost:" + porta, tokenInterno, 10, Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(30));
        try {
            cliente.addLink(new EventWallet(u1.getId(), e.getEvent_id()), null, true);
            aguardarDisponivel(e.getEvent_id(), 1);
            cliente.addLink(new EventWallet(u2.getId(), e.getEvent_id()), null, true);
            aguardarDisponivel(e.getEvent_id(), 0);

            assertEquals(2, capacidade(e.getEvent_id()).getQuant(),
                    "o naive não toca o CAS: a queda veio só da projeção");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> cliente.addLink(new EventWallet(u3.getId(), e.getEvent_id()), null, true));
            assertTrue(ex.getMessage().toLowerCase().contains("lotado"), "veio: " + ex.getMessage());
        } finally {
            cliente.close();
        }
    }

    /** #146: o listener da projeção é em lote, com o teto de {@code events.consumer.batch-size} (default 500). */
    @Test
    public void listenerDaProjecaoEhEmLoteComOTetoConfigurado() {
        MessageListenerContainer container = registry.getListenerContainer(EventAvailabilityConsumer.LISTENER_ID);
        assertTrue(container.getContainerProperties().getMessageListener() instanceof BatchMessageListener,
                "o listener recebe List<ConsumerRecord>");
        assertEquals("500", container.getContainerProperties().getKafkaConsumerProperties()
                .getProperty("max.poll.records"));
    }

    // ===================== helpers =====================

    private void aguardarDisponivel(Long eventId, int esperada) throws Exception {
        EventsTestSupport.aguardar(ESPERA, () -> {
            Integer atual = eventWalletService.disponibilidade(eventId);
            return atual != null && atual == esperada;
        }, "disponibilidade do evento " + eventId + " = " + esperada);
    }

    private void aguardarAtivos(Long eventId, int esperados) throws Exception {
        EventsTestSupport.aguardar(ESPERA, () -> {
            try {
                Linha l = lerLinha(eventId);
                return l != null && l.ativos() == esperados;
            } catch (Exception ex) {
                return false;
            }
        }, "ativos do evento " + eventId + " = " + esperados);
    }

    private record Linha(int ativos, Instant lastOccurredAt, Instant appliedAt) {
    }

    private Linha lerLinha(Long eventId) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT ativos, last_occurred_at, applied_at FROM reservation.event_availability WHERE event_id = ?")) {
            ps.setLong(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null; // a linha só nasce com o 1º evento aplicado
                }
                return new Linha(rs.getInt(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant());
            }
        }
    }

    private Instant ultimoOccurredNoLog(Long eventId) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT max(occurred_at) FROM reservation.event_store WHERE stream_type = 'reservation.wallet' "
                 + "AND partition_key = ?")) {
            ps.setString(1, String.valueOf(eventId));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Instant i = rs.getTimestamp(1).toInstant();
                assertNotNull(i);
                return i;
            }
        }
    }

}
