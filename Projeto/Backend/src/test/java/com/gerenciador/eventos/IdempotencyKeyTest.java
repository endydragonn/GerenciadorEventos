package com.gerenciador.eventos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.catalog.EventService;
import com.gerenciador.eventos.catalog.api.Event;
import com.gerenciador.eventos.identity.UserService;
import com.gerenciador.eventos.identity.api.User;
import com.gerenciador.eventos.reservation.EventCapRepository;
import com.gerenciador.eventos.reservation.EventWalletService;
import com.gerenciador.eventos.reservation.MyWalletService;
import com.gerenciador.eventos.reservation.api.EventWallet;
import com.gerenciador.eventos.reservation.api.IdempotencyConflictException;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Idempotência do {@code addLink} (issue #24): a repetição com a mesma {@code Idempotency-Key}
 * devolve o resultado da primeira e NÃO decrementa {@code eventcap} de novo — inclusive sob
 * concorrência (a PK da tabela {@code idempotency_key} serializa as requisições com a mesma chave).
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class IdempotencyKeyTest {

    @Autowired private UserService userService;
    @Autowired private MyWalletService myWalletService;
    @Autowired private EventService eventService;
    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventCapRepository eventCapRepository;
    @Autowired private DatabaseConnection databaseConnection;

    @Test
    public void repeticaoComAMesmaChaveNaoDecrementaDeNovo() throws Exception {
        User u = novoUsuario("idem-seq@example.com");
        Event e = novoEvento(u.getId(), "Idem Sequencial", 5);
        String chave = UUID.randomUUID().toString();

        EventWallet primeira = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), chave);
        // a reserva ainda está ativa: sem a chave, o 2º addLink seria "Vínculo já existente"
        EventWallet repetida = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), chave);

        assertEquals("pending", repetida.getStatus());
        assertEquals(primeira.getExpiresAt(), repetida.getExpiresAt(), "mesmo corpo: expiresAt idêntico");
        assertEquals(chave, repetida.getIdempotencyKey());
        assertEquals(4, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(), "um único decremento");
        assertEquals(1, contar("walletevent", e.getEvent_id()));
        assertEquals(1, contar("idempotency_key", e.getEvent_id()));
    }

    @Test
    public void repeticaoDepoisDeConfirmarAindaDevolveOResultadoDaPrimeira() {
        User u = novoUsuario("idem-confirm@example.com");
        Event e = novoEvento(u.getId(), "Idem Confirmada", 3);
        String chave = UUID.randomUUID().toString();

        EventWallet primeira = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), chave);
        eventWalletService.confirm(u.getId(), e.getEvent_id());
        EventWallet repetida = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), chave);

        assertEquals("pending", repetida.getStatus(), "resultado da primeira, não o estado atual");
        assertEquals(primeira.getExpiresAt(), repetida.getExpiresAt());
        assertEquals(2, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());
    }

    @Test
    public void semChaveOServicoGeraUmaEDevolve() {
        User u = novoUsuario("idem-gerada@example.com");
        Event e = novoEvento(u.getId(), "Idem Gerada", 2);

        EventWallet r = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));

        assertNotNull(r.getIdempotencyKey(), "chave gerada no servidor");
        EventWallet repetida = eventWalletService.addLink(
                new EventWallet(u.getId(), e.getEvent_id()), r.getIdempotencyKey());
        assertEquals(r.getExpiresAt(), repetida.getExpiresAt());
        assertEquals(1, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());
    }

    @Test
    public void mesmaChaveComOutroEventoOuUsuarioEhConflito() {
        User u = novoUsuario("idem-conf-u1@example.com");
        User outro = novoUsuario("idem-conf-u2@example.com");
        Event e1 = novoEvento(u.getId(), "Idem Conflito 1", 5);
        Event e2 = novoEvento(u.getId(), "Idem Conflito 2", 5);
        String chave = UUID.randomUUID().toString();
        eventWalletService.addLink(new EventWallet(u.getId(), e1.getEvent_id()), chave);

        assertThrows(IdempotencyConflictException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e2.getEvent_id()), chave));
        assertThrows(IdempotencyConflictException.class,
                () -> eventWalletService.addLink(new EventWallet(outro.getId(), e1.getEvent_id()), chave));
        assertEquals(4, eventCapRepository.findByEventId(e1.getEvent_id()).getQuant());
        assertEquals(5, eventCapRepository.findByEventId(e2.getEvent_id()).getQuant(), "conflito não reserva nada");
    }

    @Test
    public void reservaQueFalhaNaoGravaAChaveEPodeSerRepetida() {
        User creator = novoUsuario("idem-lot-creator@example.com");
        Event e = novoEvento(creator.getId(), "Idem Lotado", 1);
        User u1 = novoUsuario("idem-lot-u1@example.com");
        User u2 = novoUsuario("idem-lot-u2@example.com");
        String chave = UUID.randomUUID().toString();
        eventWalletService.addLink(new EventWallet(u1.getId(), e.getEvent_id()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(u2.getId(), e.getEvent_id()), chave));
        assertTrue(ex.getMessage().toLowerCase().contains("lotado"));

        // libera a vaga: a MESMA chave agora reserva (a falha não ficou gravada)
        eventWalletService.removeLink(u1.getId(), e.getEvent_id());
        EventWallet r = eventWalletService.addLink(new EventWallet(u2.getId(), e.getEvent_id()), chave);
        assertEquals("pending", r.getStatus());
        assertEquals(0, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());
    }

    @Test
    public void chaveGrandeDemaisEhRejeitada() {
        User u = novoUsuario("idem-invalida@example.com");
        Event e = novoEvento(u.getId(), "Idem Invalida", 2);
        assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "x".repeat(256)));
    }

    /** 8 requisições simultâneas com a MESMA chave: todas têm sucesso, 1 decremento, 1 linha. */
    @Test
    public void concorrenciaComAMesmaChaveDecrementaUmaVezSo() throws Exception {
        User u = novoUsuario("idem-conc@example.com");
        Event e = novoEvento(u.getId(), "Idem Concorrente", 5);
        String chave = UUID.randomUUID().toString();
        int n = 8;
        CyclicBarrier largada = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<EventWallet>> futuros = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futuros.add(pool.submit(() -> {
                    EventWallet ew = new EventWallet(u.getId(), e.getEvent_id()); // 1 instância por thread
                    largada.await();
                    return eventWalletService.addLink(ew, chave);
                }));
            }
            LocalDateTime esperado = null;
            for (Future<EventWallet> f : futuros) {
                EventWallet r = f.get(); // qualquer exceção (ex.: "Vínculo já existente") falha o teste
                assertEquals("pending", r.getStatus());
                if (esperado == null) esperado = r.getExpiresAt();
                assertEquals(esperado, r.getExpiresAt(), "todas devolvem o resultado da mesma reserva");
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(4, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(), "um único decremento");
        assertEquals(1, contar("walletevent", e.getEvent_id()));
        assertEquals(1, contar("idempotency_key", e.getEvent_id()));
    }

    // ===================== helpers =====================

    private User novoUsuario(String email) {
        User u = new User();
        u.setName("Idem " + email);
        u.setEmail(email);
        u.setFone("118" + Math.abs(email.hashCode() % 100000000));
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

    private int contar(String tabela, Long eventId) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM " + tabela + " WHERE event_id = ?")) {
            ps.setLong(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
