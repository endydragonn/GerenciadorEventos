package com.gerenciador.eventos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.gerenciador.eventos.reservation.ReadModelRefreshService;
import com.gerenciador.eventos.identity.UserService;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Trava o caminho de leitura INGÊNUO ({@code reservation.readpath=naive}): lê o read model
 * defasado {@code event_availability} e insere em {@code walletevent} SEM CAS — nunca toca
 * {@code eventcap}. É o braço de controle do experimento de concorrência da Fase 2 e
 * OVERSELLA de propósito quando o read model está defasado.
 *
 * <p>{@code readmodel.availability.refresh.ms=3600000}: o job nunca dispara sozinho durante
 * a suíte; os testes chamam {@link ReadModelRefreshService#refresh()} na mão para controlar
 * exatamente o quão defasado o read model está.</p>
 */
@SpringBootTest(properties = {
        "reservation.readpath=naive",
        "readmodel.availability.refresh.ms=3600000"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class EventWalletNaivePathTest {

    @Autowired private UserService userService;
    @Autowired private MyWalletService myWalletService;
    @Autowired private EventService eventService;
    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventCapRepository eventCapRepository;
    @Autowired private EventWalletRepository eventWalletRepository;
    @Autowired private ReadModelRefreshService readModelRefreshService;
    @Autowired private DatabaseConnection databaseConnection;

    /**
     * Overselling demonstrado: duas requisições que leem o mesmo {@code available > 0}
     * defasado (sem refresh entre elas) passam ambas; {@code eventcap} não se mexe.
     */
    @Test
    public void doisAddLinkNaMesmaJanelaOversellamSemTocarOCas() throws Exception {
        User creator = novoUsuario("naive-oversell-creator@example.com", "naive-fone-01");
        Event e = novoEvento(creator.getId(), "Naive Oversell Cap1", 1);
        User u1 = novoUsuario("naive-oversell-u1@example.com", "naive-fone-02");
        User u2 = novoUsuario("naive-oversell-u2@example.com", "naive-fone-03");

        readModelRefreshService.refresh(); // available = 1

        EventWallet r1 = eventWalletService.addLink(new EventWallet(u1.getId(), e.getEvent_id()));
        EventWallet r2 = eventWalletService.addLink(new EventWallet(u2.getId(), e.getEvent_id()));
        assertEquals("pending", r1.getStatus());
        assertEquals("pending", r2.getStatus(), "2ª reserva na mesma janela de lag também passa");

        eventWalletService.confirm(u1.getId(), e.getEvent_id());
        eventWalletService.confirm(u2.getId(), e.getEvent_id());

        assertEquals(2, contarWalletevent(e.getEvent_id(), "confirmed"),
                "2 linhas confirmadas para capacity 1 — overselling");
        assertEquals(1, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(),
                "o caminho ingênuo não tocou o CAS: eventcap.quant continua 1");
    }

    /** Sem refresh após criar o evento, {@code available} é {@code null} e a reserva falha. */
    @Test
    public void semRefreshDisponibilidadeNaoCalculadaBloqueiaAReserva() {
        User creator = novoUsuario("naive-null-creator@example.com", "naive-fone-04");
        Event e = novoEvento(creator.getId(), "Naive Disp Null", 3);
        User u = novoUsuario("naive-null-u@example.com", "naive-fone-05");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id())));
        assertTrue(ex.getMessage().toLowerCase().contains("ainda não calculada")
                        || ex.getMessage().toLowerCase().contains("ainda nao calculada"),
                "mensagem deve indicar disponibilidade não calculada, veio: " + ex.getMessage());
    }

    /** Reserva duplicada do mesmo usuário é barrada antes do caminho ingênuo. */
    @Test
    public void reservaDuplicadaDoMesmoUsuarioEhRejeitada() {
        User creator = novoUsuario("naive-dup-creator@example.com", "naive-fone-06");
        Event e = novoEvento(creator.getId(), "Naive Duplicada", 5);
        User u = novoUsuario("naive-dup-u@example.com", "naive-fone-07");

        readModelRefreshService.refresh();
        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id())));
        assertTrue(ex.getMessage().toLowerCase().contains("já existente")
                        || ex.getMessage().toLowerCase().contains("ja existente"),
                "veio: " + ex.getMessage());
    }

    /** {@code available == 0} no read model defasado => "Evento lotado". */
    @Test
    public void disponibilidadeZeroNoReadModelBloqueiaAReserva() {
        User creator = novoUsuario("naive-lotado-creator@example.com", "naive-fone-08");
        Event e = novoEvento(creator.getId(), "Naive Lotado", 1);
        User u1 = novoUsuario("naive-lotado-u1@example.com", "naive-fone-09");
        User u2 = novoUsuario("naive-lotado-u2@example.com", "naive-fone-10");

        readModelRefreshService.refresh();
        eventWalletService.addLink(new EventWallet(u1.getId(), e.getEvent_id()));
        readModelRefreshService.refresh(); // agora available = 0 (1 reserva ativa)

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(u2.getId(), e.getEvent_id())));
        assertTrue(ex.getMessage().toLowerCase().contains("lotado"), "veio: " + ex.getMessage());
    }

    // ===================== helpers =====================

    private User novoUsuario(String email, String fone) {
        User u = new User();
        u.setName("Naive " + email);
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

    private int contarWalletevent(Long eventId, String status) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM walletevent WHERE event_id = ? AND status = ?")) {
            ps.setLong(1, eventId);
            ps.setString(2, status);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
