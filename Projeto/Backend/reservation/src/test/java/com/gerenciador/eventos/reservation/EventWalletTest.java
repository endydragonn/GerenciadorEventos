package com.gerenciador.eventos.reservation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.concurrent.CyclicBarrier;

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

import com.gerenciador.eventos.reservationclient.api.EventCap;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.reservation.EventCapRepository;
import com.gerenciador.eventos.reservation.EventWalletRepository;
import com.gerenciador.eventos.reservation.EventWalletService;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class EventWalletTest extends ReservationTestSupport {
    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventCapRepository eventCapRepository;
    @Autowired private EventWalletRepository eventWalletRepository;
    @Autowired private DatabaseConnection databaseConnection;

    @Test
    public void reservaDebitaUmaVagaEFicaPending() {
        Usuario u = novoUsuario("rc-owner@example.com");
        Evento e = novoEvento(u.getId(), "Evento RC", 5);

        EventWallet reserved = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));

        assertEquals("pending", reserved.getStatus());
        assertNotNull(reserved.getExpiresAt());
        assertEquals("pending", eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()));
        assertEquals(4, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(), "reserva deve debitar 1 vaga");
    }

    @Test
    public void confirmTransformaPendingEmConfirmedSemMexerNaVaga() {
        Usuario u = novoUsuario("rc-confirm@example.com");
        Evento e = novoEvento(u.getId(), "Evento RC Confirm", 5);
        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));

        EventWallet confirmed = eventWalletService.confirm(u.getId(), e.getEvent_id());

        assertEquals("confirmed", confirmed.getStatus());
        assertEquals("confirmed", eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()));
        assertEquals(4, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(), "confirmar não debita de novo");
    }

    @Test
    public void reservaDuplicadaEhRejeitadaSemDebitarOutraVaga() {
        Usuario u = novoUsuario("rc-dup@example.com");
        Evento e = novoEvento(u.getId(), "Evento RC Dup", 5);
        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        eventWalletService.confirm(u.getId(), e.getEvent_id());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id())));

        assertEquals("Vínculo já existente", ex.getMessage());
        assertEquals(4, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(), "a duplicada não debita vaga");
    }

    /** Reserva depois do {@code buy_time_limit}: 400 (IllegalArgumentException), sem vaga debitada, sem linha, sem evento. */
    @Test
    public void janelaDeCompraEncerradaRecusaAReservaSemTocarNada() throws Exception {
        Usuario u = novoUsuario("janela-u@example.com");
        Evento e = novoEvento(u.getId(), "Evento Janela Encerrada", 5, LocalDateTime.now().minusMinutes(1));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id())));

        assertEquals("Janela de compra encerrada para este evento", ex.getMessage());
        assertEquals(5, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(), "eventcap intocado");
        assertNull(eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()), "nenhuma walletevent");
        assertEquals(0, eventosNoStream(u.getId(), e.getEvent_id()), "nenhum evento no event_store");
    }

    @Test
    public void janelaDeCompraAindaAbertaAceitaAReserva() {
        Usuario u = novoUsuario("janela-aberta-u@example.com");
        Evento e = novoEvento(u.getId(), "Evento Janela Aberta", 5, LocalDateTime.now().plusHours(1));

        EventWallet r = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));

        assertEquals("pending", r.getStatus());
        assertEquals(4, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());
    }

    @Test
    public void testEventoLotado() {
        Usuario creator = novoUsuario("lot-creator@example.com");
        Evento e = novoEvento(creator.getId(), "Evento Lotado", 1);

        Usuario u1 = novoUsuario("lot-u1@example.com");
        Usuario u2 = novoUsuario("lot-u2@example.com");

        eventWalletService.addLink(new EventWallet(u1.getId(), e.getEvent_id())); // pega a única vaga

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(u2.getId(), e.getEvent_id())));
        assertTrue(ex.getMessage().toLowerCase().contains("lotado"));

        assertEquals(0, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());
    }

    @Test
    public void testEventoIlimitado() {
        Usuario creator = novoUsuario("ilim-creator@example.com");
        Evento e = novoEvento(creator.getId(), "Evento Ilimitado", -1); // -1 = sem limite

        EventCap cap = eventCapRepository.findByEventId(e.getEvent_id());
        assertEquals(-1, cap.getCapacity());
        assertTrue(cap.isUnlimited());

        // várias inscrições passam sem debitar vaga
        for (int i = 0; i < 3; i++) {
            Usuario u = novoUsuario("ilim-u" + i + "@example.com");
            EventWallet r = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
            assertEquals("pending", r.getStatus());
            eventWalletService.confirm(u.getId(), e.getEvent_id());
        }
        assertEquals(-1, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(),
                "quant do evento ilimitado não muda");
    }

    @Test
    public void testCancelReleasesSeat() {
        Usuario creator = novoUsuario("cancel-creator@example.com");
        Evento e = novoEvento(creator.getId(), "Evento Cancel", 2);
        Usuario u = novoUsuario("cancel-u@example.com");

        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        eventWalletService.confirm(u.getId(), e.getEvent_id());
        assertEquals(1, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());

        eventWalletService.removeLink(u.getId(), e.getEvent_id());
        assertEquals(2, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(),
                "cancelamento devolve a vaga");
    }

    @Test
    public void testRemoveLinkRepetidoDevolveVagaUmaVezSo() {
        Usuario creator = novoUsuario("dbl-creator@example.com");
        Evento e = novoEvento(creator.getId(), "Evento Cancel Repetido", 1);
        Usuario u = novoUsuario("dbl-u@example.com");

        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        eventWalletService.confirm(u.getId(), e.getEvent_id());
        assertEquals(0, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());

        eventWalletService.removeLink(u.getId(), e.getEvent_id());
        assertEquals(1, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(),
                "primeiro cancelamento devolve a vaga");

        // 2ª chamada (retry de rede / clique repetido): a reserva já está cancelled,
        // não pode devolver vaga de novo
        eventWalletService.removeLink(u.getId(), e.getEvent_id());
        assertEquals(1, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(),
                "cancelamento repetido não devolve vaga de novo");
    }

    @Test
    public void testRemoveLinkConcorrenteNaoCausaOverselling() throws Exception {
        // capacidade no "meio da faixa": o guard quant < capacity do release, sozinho,
        // não impede a devolução dupla de vaga.
        Usuario creator = novoUsuario("cc-creator@example.com");
        Evento e = novoEvento(creator.getId(), "Evento Cancel Concorrente", 3);
        Usuario a = novoUsuario("cc-a@example.com");
        Usuario b = novoUsuario("cc-b@example.com");
        Usuario c = novoUsuario("cc-c@example.com");
        for (Usuario u : new Usuario[] { a, b, c }) {
            eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
            eventWalletService.confirm(u.getId(), e.getEvent_id());
        }
        assertEquals(0, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());

        // duas remoções concorrentes do MESMO vínculo (dois cliques / abas)
        final Long userId = a.getId();
        final Long eventId = e.getEvent_id();
        CyclicBarrier largada = new CyclicBarrier(2);
        Runnable cancelar = () -> {
            try {
                largada.await();
                eventWalletService.removeLink(userId, eventId);
            } catch (Exception ignored) {
                // segunda chamada pode legitimamente lançar/retornar sem efeito
            }
        };
        Thread t1 = new Thread(cancelar);
        Thread t2 = new Thread(cancelar);
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertEquals(1, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(),
                "cancelar o mesmo vínculo 2x devolve no máximo 1 vaga (sem overselling)");
        assertEquals("cancelled",
                eventWalletRepository.currentStatus(a.getId(), e.getEvent_id()));
    }

    private int eventosNoStream(Long userId, Long eventId) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM reservation.event_store WHERE stream_type = ? AND stream_id = ?")) {
            ps.setString(1, ReservationEvents.STREAM_TYPE);
            ps.setString(2, ReservationEvents.streamId(userId, eventId));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
