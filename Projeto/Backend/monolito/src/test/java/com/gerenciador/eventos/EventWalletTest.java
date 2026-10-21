package com.gerenciador.eventos;
import java.time.LocalDateTime;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.catalog.api.Event;
import com.gerenciador.eventos.reservation.api.EventCap;
import com.gerenciador.eventos.reservation.api.EventWallet;
import com.gerenciador.eventos.identity.api.User;
import com.gerenciador.eventos.reservation.EventCapRepository;
import com.gerenciador.eventos.reservation.EventWalletRepository;
import com.gerenciador.eventos.catalog.EventService;
import com.gerenciador.eventos.reservation.EventWalletService;
import com.gerenciador.eventos.reservation.MyWalletService;
import com.gerenciador.eventos.identity.UserService;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class EventWalletTest {

    @Autowired private UserService userService;
    @Autowired private MyWalletService myWalletService;
    @Autowired private EventService eventService;
    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventCapRepository eventCapRepository;
    @Autowired private EventWalletRepository eventWalletRepository;

    private User newUser(String email) {
        User u = new User();
        u.setName("User " + email);
        u.setEmail(email);
        u.setFone("119" + Math.abs(email.hashCode() % 100000000));
        u = userService.createUser(u);
        myWalletService.ensureExists(u.getId());
        return u;
    }

    private Event newEvent(Long creatorId, String name, int capacity) {
        Event e = new Event();
        e.setCreator_id(creatorId);
        e.setEvent_name(name);
        e.setIs_EAD(true);
        e.setEvent_date(LocalDateTime.now().plusDays(10));
        e.setBuy_time_limit(null);
        e.setLot_quantity(capacity);
        e.setDescription("desc");
        return eventService.createEvent(e);
    }

    @Test
    public void testReserveConfirmDecrementsAndConfirms() {
        User u = newUser("rc-owner@example.com");
        Event e = newEvent(u.getId(), "Evento RC", 5);

        EventWallet reserved = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        assertEquals("pending", reserved.getStatus());
        assertNotNull(reserved.getExpiresAt());

        EventCap cap = eventCapRepository.findByEventId(e.getEvent_id());
        assertEquals(4, cap.getQuant(), "reserva deve debitar 1 vaga");

        EventWallet confirmed = eventWalletService.confirm(u.getId(), e.getEvent_id());
        assertEquals("confirmed", confirmed.getStatus());

        // reserva duplicada é rejeitada
        assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id())));
    }

    @Test
    public void testEventoLotado() {
        User creator = newUser("lot-creator@example.com");
        Event e = newEvent(creator.getId(), "Evento Lotado", 1);

        User u1 = newUser("lot-u1@example.com");
        User u2 = newUser("lot-u2@example.com");

        eventWalletService.addLink(new EventWallet(u1.getId(), e.getEvent_id())); // pega a única vaga

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(u2.getId(), e.getEvent_id())));
        assertTrue(ex.getMessage().toLowerCase().contains("lotado"));

        assertEquals(0, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());
    }

    @Test
    public void testEventoIlimitado() {
        User creator = newUser("ilim-creator@example.com");
        Event e = newEvent(creator.getId(), "Evento Ilimitado", -1); // -1 = sem limite

        EventCap cap = eventCapRepository.findByEventId(e.getEvent_id());
        assertEquals(-1, cap.getCapacity());
        assertTrue(cap.isUnlimited());

        // várias inscrições passam sem debitar vaga
        for (int i = 0; i < 3; i++) {
            User u = newUser("ilim-u" + i + "@example.com");
            EventWallet r = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
            assertEquals("pending", r.getStatus());
            eventWalletService.confirm(u.getId(), e.getEvent_id());
        }
        assertEquals(-1, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(),
                "quant do evento ilimitado não muda");
    }

    @Test
    public void testCancelReleasesSeat() {
        User creator = newUser("cancel-creator@example.com");
        Event e = newEvent(creator.getId(), "Evento Cancel", 2);
        User u = newUser("cancel-u@example.com");

        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        eventWalletService.confirm(u.getId(), e.getEvent_id());
        assertEquals(1, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());

        eventWalletService.removeLink(u.getId(), e.getEvent_id());
        assertEquals(2, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(),
                "cancelamento devolve a vaga");
    }

    @Test
    public void testRemoveLinkRepetidoDevolveVagaUmaVezSo() {
        User creator = newUser("dbl-creator@example.com");
        Event e = newEvent(creator.getId(), "Evento Cancel Repetido", 1);
        User u = newUser("dbl-u@example.com");

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
        User creator = newUser("cc-creator@example.com");
        Event e = newEvent(creator.getId(), "Evento Cancel Concorrente", 3);
        User a = newUser("cc-a@example.com");
        User b = newUser("cc-b@example.com");
        User c = newUser("cc-c@example.com");
        for (User u : new User[] { a, b, c }) {
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
}
