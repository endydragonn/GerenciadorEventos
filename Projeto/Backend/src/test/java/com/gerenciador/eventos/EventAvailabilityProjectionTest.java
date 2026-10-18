package com.gerenciador.eventos;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.gerenciador.eventos.TestSupport.EventsTestSupport;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.catalog.EventService;
import com.gerenciador.eventos.catalog.api.Event;
import com.gerenciador.eventos.identity.UserService;
import com.gerenciador.eventos.identity.api.User;
import com.gerenciador.eventos.reservation.EventAvailabilityProjection;
import com.gerenciador.eventos.reservation.EventAvailabilityRepository;
import com.gerenciador.eventos.reservation.EventCapRepository;
import com.gerenciador.eventos.reservation.EventWalletService;
import com.gerenciador.eventos.reservation.MyWalletService;
import com.gerenciador.eventos.reservation.ReservationEvents;
import com.gerenciador.eventos.reservation.api.EventWallet;
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
@SpringBootTest(properties = {
        "events.relay.enabled=true", "events.relay.interval-ms=50", "events.consumer.enabled=true"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class EventAvailabilityProjectionTest {

    private static final Duration ESPERA = Duration.ofSeconds(60);

    @Autowired private UserService userService;
    @Autowired private MyWalletService myWalletService;
    @Autowired private EventService eventService;
    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventAvailabilityRepository eventAvailabilityRepository;
    @Autowired private EventCapRepository eventCapRepository;
    @Autowired private IdempotentConsumer idempotentConsumer;
    @Autowired private EventStore eventStore;
    @Autowired private DatabaseConnection databaseConnection;

    @Test
    public void disponibilidadeEhCapacidadeMenosReservasAtivasDepoisDoConsumer() throws Exception {
        User creator = novoUsuario("proj-cap-creator@example.com", "proj-fone-01");
        Event e = novoEvento(creator.getId(), "Proj Cap5", 5);
        assertEquals(5, eventAvailabilityRepository.findAvailable(e.getEvent_id()), "sem reservas: a capacidade inteira");

        for (int i = 0; i < 2; i++) {
            User u = novoUsuario("proj-cap-u" + i + "@example.com", "proj-fone-1" + i);
            eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
            eventWalletService.confirm(u.getId(), e.getEvent_id());
        }

        aguardarDisponivel(e.getEvent_id(), 3); // capacity 5 - 2 reservas ativas
    }

    @Test
    public void eventoIlimitadoFicaMenosUmNaLeitura() throws Exception {
        User creator = novoUsuario("proj-ilim-creator@example.com", "proj-fone-02");
        Event e = novoEvento(creator.getId(), "Proj Ilimitado", -1);
        User u = novoUsuario("proj-ilim-u@example.com", "proj-fone-20");

        assertEquals(-1, eventAvailabilityRepository.findAvailable(e.getEvent_id()));
        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        aguardarAtivos(e.getEvent_id(), 1);
        assertEquals(-1, eventAvailabilityRepository.findAvailable(e.getEvent_id()),
                "capacity -1 => -1, com ou sem ativos na projeção");
    }

    @Test
    public void cancelamentoEExpiracaoDevolvemDisponibilidadePeloConsumer() throws Exception {
        User creator = novoUsuario("proj-cancel-creator@example.com", "proj-fone-03");
        Event e = novoEvento(creator.getId(), "Proj Cancel", 2);
        User u = novoUsuario("proj-cancel-u@example.com", "proj-fone-30");

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
        User creator = novoUsuario("proj-lag-creator@example.com", "proj-fone-04");
        Event e = novoEvento(creator.getId(), "Proj Lag", 3);
        User u = novoUsuario("proj-lag-u@example.com", "proj-fone-40");

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
        User creator = novoUsuario("proj-dup-creator@example.com", "proj-fone-05");
        Event e = novoEvento(creator.getId(), "Proj Dup", 4);
        User u = novoUsuario("proj-dup-u@example.com", "proj-fone-50");

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
        assertEquals(3, eventAvailabilityRepository.findAvailable(e.getEvent_id()));
    }

    /**
     * Ponta a ponta do braço de controle (#31): {@code addLink} com {@code reservation.readpath=naive} -> append
     * {@code SlotReserved} -> relay -> tópico -> consumer -> {@code ativos} -> {@code findAvailable}. O naive nunca
     * toca {@code eventcap.quant} (continua na capacidade), então a disponibilidade que cai só pode vir da projeção
     * real; e, com ela em 0, o próximo {@code addLink} é barrado ("Evento lotado") por essa leitura.
     */
    @Test
    public void addLinkNaiveRefleteNaDisponibilidadePelaProjecaoReal() throws Exception {
        User creator = novoUsuario("proj-naive-creator@example.com", "proj-fone-06");
        Event e = novoEvento(creator.getId(), "Proj Naive", 2);
        User u1 = novoUsuario("proj-naive-u1@example.com", "proj-fone-60");
        User u2 = novoUsuario("proj-naive-u2@example.com", "proj-fone-61");
        User u3 = novoUsuario("proj-naive-u3@example.com", "proj-fone-62");

        Object servico = AopTestUtils.getUltimateTargetObject(eventWalletService);
        Object original = ReflectionTestUtils.getField(servico, "readPath");
        ReflectionTestUtils.setField(servico, "readPath", "naive");
        try {
            eventWalletService.addLink(new EventWallet(u1.getId(), e.getEvent_id()));
            aguardarDisponivel(e.getEvent_id(), 1);
            eventWalletService.addLink(new EventWallet(u2.getId(), e.getEvent_id()));
            aguardarDisponivel(e.getEvent_id(), 0);

            assertEquals(2, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(),
                    "o naive não toca o CAS: a queda veio só da projeção");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> eventWalletService.addLink(new EventWallet(u3.getId(), e.getEvent_id())));
            assertTrue(ex.getMessage().toLowerCase().contains("lotado"), "veio: " + ex.getMessage());
        } finally {
            ReflectionTestUtils.setField(servico, "readPath", original);
        }
    }

    // ===================== helpers =====================

    private void aguardarDisponivel(Long eventId, int esperada) throws Exception {
        EventsTestSupport.aguardar(ESPERA, () -> {
            Integer atual = eventAvailabilityRepository.findAvailable(eventId);
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
                 "SELECT ativos, last_occurred_at, applied_at FROM event_availability WHERE event_id = ?")) {
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
                 "SELECT max(occurred_at) FROM event_store WHERE stream_type = 'reservation.wallet' "
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

    private User novoUsuario(String email, String fone) {
        User u = new User();
        u.setName("Proj " + email);
        u.setEmail(email);
        u.setFone(fone);
        u = userService.createUser(u);
        myWalletService.ensureExists(u.getId());
        return u;
    }

    private Event novoEvento(Long criadorId, String nome, int capacidade) {
        Event e = new Event();
        e.setCreator_id(criadorId);
        e.setEvent_name(nome);
        e.setIs_EAD(true);
        e.setEvent_date(LocalDateTime.now().plusDays(10));
        e.setBuy_time_limit(null);
        e.setLot_quantity(capacidade);
        e.setDescription("desc");
        return eventService.createEvent(e);
    }
}
