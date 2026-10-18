package com.gerenciador.eventos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.catalog.api.Event;
import com.gerenciador.eventos.reservation.api.EventWallet;
import com.gerenciador.eventos.identity.api.User;
import com.gerenciador.eventos.reservation.EventCapRepository;
import com.gerenciador.eventos.reservation.EventWalletRepository;
import com.gerenciador.eventos.catalog.EventService;
import com.gerenciador.eventos.reservation.EventWalletService;
import com.gerenciador.eventos.reservation.MyWalletService;
import com.gerenciador.eventos.identity.UserService;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Trava em teste a máquina de estados de {@code walletevent} e o UPSERT que revive reservas
 * (issue #7 — verificação da implementação EventCap/CAS de 2026-09-01).
 *
 * <ul>
 *   <li><b>D)</b> {@code EventWalletRepository.reservePending} revive linhas
 *       {@code cancelled}/{@code expired} e NÃO pisa em {@code pending}/{@code confirmed}
 *       (guard {@code WHERE walletevent.status IN ('expired','cancelled')}), sempre com a
 *       vaga de {@code eventcap} debitada exatamente uma vez no total;</li>
 *   <li><b>E)</b> transições {@code pending -> confirmed}, {@code pending -> cancelled} e
 *       {@code confirmed -> cancelled}.</li>
 * </ul>
 *
 * <p>O caso {@code pending -> expired} é território da issue #8 e NÃO é coberto aqui além do
 * item D (onde o {@code expired} é forjado por {@code UPDATE} direto, sem o scheduler).</p>
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class WalletEventStateMachineTest {

    @Autowired private UserService userService;
    @Autowired private MyWalletService myWalletService;
    @Autowired private EventService eventService;
    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventCapRepository eventCapRepository;
    @Autowired private EventWalletRepository eventWalletRepository;
    @Autowired private DatabaseConnection databaseConnection;

    // ===================== D) UPSERT revive =====================

    @Test
    public void reReservaAposCancelamentoVoltaAPendingEDebitaVagaUmaVezSo() {
        User u = novoUsuario("sm7-cancel-revive@example.com", "sm7-fone-01");
        Event e = novoEvento(u.getId(), "Evento SM7 Cancel Revive", 5);

        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        eventWalletService.confirm(u.getId(), e.getEvent_id());
        eventWalletService.removeLink(u.getId(), e.getEvent_id()); // -> cancelled, vaga devolvida
        assertEquals("cancelled", eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()));
        assertEquals(5, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(),
                "cancelamento devolve a vaga antes da re-reserva");

        EventWallet revivida = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));

        assertEquals("pending", revivida.getStatus(), "re-reserva revive a linha para pending");
        assertNotNull(revivida.getExpiresAt(), "reserva revivida tem expires_at");
        assertEquals("pending", eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()));
        assertEquals(4, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(),
                "no total a vaga foi debitada exatamente uma vez (não duas)");
        assertExpiresAtNoFuturo(u.getId(), e.getEvent_id());
    }

    @Test
    public void reReservaAposExpiracaoVoltaAPendingEDebitaVagaDeNovo() throws Exception {
        User u = novoUsuario("sm7-expired-revive@example.com", "sm7-fone-02");
        Event e = novoEvento(u.getId(), "Evento SM7 Expired Revive", 5);

        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        assertEquals(4, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());

        // força expired por UPDATE direto (sem ReservationExpiryService — isso é da issue #8)
        // e devolve a vaga manualmente pra deixar o estado consistente, como faria o job.
        try (Connection conn = databaseConnection.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE walletevent SET status = 'expired', expires_at = NULL "
                  + "WHERE user_id = ? AND event_id = ?")) {
                ps.setLong(1, u.getId());
                ps.setLong(2, e.getEvent_id());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE eventcap SET quant = quant + 1 "
                  + "WHERE event_id = ? AND capacity <> -1 AND quant < capacity")) {
                ps.setLong(1, e.getEvent_id());
                ps.executeUpdate();
            }
        }
        assertEquals(5, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());

        EventWallet revivida = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));

        assertEquals("pending", revivida.getStatus());
        assertNotNull(revivida.getExpiresAt());
        assertEquals("pending", eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()));
        assertEquals(4, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(),
                "re-reserva sobre expired debita a vaga de novo");
        assertExpiresAtNoFuturo(u.getId(), e.getEvent_id());
    }

    @Test
    public void reReservaSobreConfirmedNaoPisaNaLinhaNemNaVaga() throws Exception {
        User u = novoUsuario("sm7-confirmed-noop@example.com", "sm7-fone-03");
        Event e = novoEvento(u.getId(), "Evento SM7 Confirmed NoOp", 5);

        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        eventWalletService.confirm(u.getId(), e.getEvent_id());
        assertEquals(4, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());

        // caminho do serviço: barra em existsActive antes do CAS
        assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id())));
        assertEquals("confirmed", eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()));
        assertEquals(4, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());

        // guard do próprio UPSERT (WHERE status IN ('expired','cancelled')): chamada direta
        // ao repository sobre uma linha confirmed não pode revivê-la.
        try (Connection conn = databaseConnection.getConnection()) {
            int versao = eventWalletRepository.reservePending(
                    conn, u.getId(), e.getEvent_id(), LocalDateTime.now().plusSeconds(120));
            assertEquals(0, versao, "reservePending não pode reviver uma reserva confirmed");
        }
        assertEquals("confirmed", eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()));
        assertEquals(4, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(),
                "guard do UPSERT preserva a vaga");
    }

    // ===================== E) máquina de estados =====================

    @Test
    public void transicaoPendingParaConfirmed() {
        User u = novoUsuario("sm7-p2c@example.com", "sm7-fone-11");
        Event e = novoEvento(u.getId(), "Evento SM7 P2C", 3);

        EventWallet r = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        assertEquals("pending", r.getStatus());

        eventWalletService.confirm(u.getId(), e.getEvent_id());
        assertEquals("confirmed", eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()));
        assertNull(expiresAt(u.getId(), e.getEvent_id()), "confirmed zera expires_at");
    }

    @Test
    public void transicaoPendingParaCancelled() {
        User u = novoUsuario("sm7-p2x@example.com", "sm7-fone-12");
        Event e = novoEvento(u.getId(), "Evento SM7 P2X", 3);

        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id())); // pending
        assertEquals(2, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());

        eventWalletService.removeLink(u.getId(), e.getEvent_id()); // pending -> cancelled
        assertEquals("cancelled", eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()));
        assertEquals(3, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(),
                "cancelar uma reserva pending devolve a vaga");
    }

    @Test
    public void transicaoConfirmedParaCancelled() {
        User u = novoUsuario("sm7-c2x@example.com", "sm7-fone-13");
        Event e = novoEvento(u.getId(), "Evento SM7 C2X", 3);

        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        eventWalletService.confirm(u.getId(), e.getEvent_id());
        assertEquals(2, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());

        eventWalletService.removeLink(u.getId(), e.getEvent_id()); // confirmed -> cancelled
        assertEquals("cancelled", eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()));
        assertEquals(3, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(),
                "cancelar uma inscrição confirmed devolve a vaga");
    }

    // ===================== helpers =====================

    private User novoUsuario(String email, String fone) {
        User u = new User();
        u.setName("SM7 " + email);
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

    private LocalDateTime expiresAt(Long userId, Long eventId) {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT expires_at FROM walletevent WHERE user_id = ? AND event_id = ?")) {
            ps.setLong(1, userId);
            ps.setLong(2, eventId);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "linha walletevent não encontrada");
                var ts = rs.getTimestamp("expires_at");
                return ts == null ? null : ts.toLocalDateTime();
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private void assertExpiresAtNoFuturo(Long userId, Long eventId) {
        LocalDateTime exp = expiresAt(userId, eventId);
        assertNotNull(exp, "expires_at nulo numa reserva pending");
        // a reserva revivida acabou de setar expires_at = now + max_time_in_line (default 120s),
        // então tem de estar estritamente no futuro.
        assertTrue(exp.isAfter(LocalDateTime.now()),
                "expires_at de reserva revivida deveria estar no futuro, veio: " + exp);
    }
}
