package com.gerenciador.eventos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.gerenciador.eventos.POJO.Event;
import com.gerenciador.eventos.POJO.EventWallet;
import com.gerenciador.eventos.POJO.User;
import com.gerenciador.eventos.Repository.EventCapRepository;
import com.gerenciador.eventos.Repository.EventWalletRepository;
import com.gerenciador.eventos.Service.EventService;
import com.gerenciador.eventos.Service.EventWalletService;
import com.gerenciador.eventos.Service.MyWalletService;
import com.gerenciador.eventos.Service.ReservationExpiryService;
import com.gerenciador.eventos.Service.UserService;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.config.DatabaseConnection;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
// Estica o intervalo do @Scheduled de ReservationExpiryService para 1h (initialDelay e
// fixedDelay), bem além da duração da suíte, para que o job nunca dispare sozinho em
// background durante os testes; as chamadas explícitas a expireStaleReservations() seguem
// iguais.
@TestPropertySource(properties = "reservation.expiry.scan.ms=3600000")
public class ReservationExpiryTest {

    @Autowired private UserService userService;
    @Autowired private MyWalletService myWalletService;
    @Autowired private EventService eventService;
    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventCapRepository eventCapRepository;
    @Autowired private EventWalletRepository eventWalletRepository;
    @Autowired private ReservationExpiryService reservationExpiryService;
    @Autowired private DatabaseConnection databaseConnection;

    @Test
    public void staleReservationIsExpiredAndSeatReturned() throws Exception {
        User creator = new User();
        creator.setName("Exp Creator");
        creator.setEmail("exp-creator@example.com");
        creator.setFone("11955555555");
        creator = userService.createUser(creator);

        User u = new User();
        u.setName("Exp User");
        u.setEmail("exp-user@example.com");
        u.setFone("11966666666");
        u = userService.createUser(u);
        myWalletService.ensureExists(u.getId());

        Event e = new Event();
        e.setCreator_id(creator.getId());
        e.setEvent_name("Evento Expiracao");
        e.setIs_EAD(true);
        e.setEvent_date(LocalDateTime.now().plusDays(10));
        e.setLot_quantity(3);
        e.setDescription("desc");
        e = eventService.createEvent(e);

        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        assertEquals(2, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());

        // força expires_at para o passado
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE walletevent SET expires_at = CURRENT_TIMESTAMP - interval '1 minute' "
               + "WHERE user_id = ? AND event_id = ?")) {
            ps.setLong(1, u.getId());
            ps.setLong(2, e.getEvent_id());
            ps.executeUpdate();
        }

        reservationExpiryService.expireStaleReservations();

        assertEquals("expired", eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()));
        assertEquals(3, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(),
                "vaga deve voltar após expiração");
    }

    /**
     * A corrida do aceite ("sem pisar em confirmação concorrente"): se o usuário confirma a
     * reserva na janela entre a varredura do job ({@code findExpiredPending}) e o
     * {@code UPDATE} de {@code expireIfStale}, o job NÃO pode expirar a linha nem devolver a
     * vaga. Exercita diretamente o caminho condicional de {@code expireIfStale}
     * (guard {@code status = 'pending'}).
     */
    @Test
    public void concurrentConfirmIsNotClobberedByExpiryJob() throws Exception {
        User creator = new User();
        creator.setName("Exp Race Creator");
        creator.setEmail("exp-race-creator@example.com");
        creator.setFone("11955550001");
        creator = userService.createUser(creator);

        User u = new User();
        u.setName("Exp Race User");
        u.setEmail("exp-race-user@example.com");
        u.setFone("11966660001");
        u = userService.createUser(u);
        myWalletService.ensureExists(u.getId());

        Event e = new Event();
        e.setCreator_id(creator.getId());
        e.setEvent_name("Evento Expiracao Corrida");
        e.setIs_EAD(true);
        e.setEvent_date(LocalDateTime.now().plusDays(10));
        e.setLot_quantity(5);
        e.setDescription("desc");
        e = eventService.createEvent(e);

        long userId = u.getId();
        long eventId = e.getEvent_id();

        eventWalletService.addLink(new EventWallet(userId, eventId));
        assertEquals(4, eventCapRepository.findByEventId(eventId).getQuant(),
                "reserva pending deve debitar 1 vaga (capacity - 1)");

        // força expires_at para o passado (mesma UPDATE parametrizada do teste happy-path)
        forcarExpiresAtNoPassado(userId, eventId);

        // varredura do job: captura a linha ainda pending + vencida
        List<EventWallet> capturada = eventWalletRepository.findExpiredPending();
        assertTrue(contem(capturada, userId, eventId),
                "a linha deve aparecer como pending vencida no momento da varredura");

        // confirmação concorrente: acontece ANTES de o job agir sobre a linha capturada
        eventWalletService.confirm(userId, eventId);

        // confirm zera expires_at; re-força para o passado para que o ÚNICO guard que ainda
        // barra o expireIfStale seja o status = 'pending', não o expires_at.
        forcarExpiresAtNoPassado(userId, eventId);

        // só então o job age sobre a linha capturada
        reservationExpiryService.expireStaleReservations();
        try (Connection conn = databaseConnection.getConnection()) {
            assertFalse(eventWalletRepository.expireIfStale(conn, userId, eventId),
                    "expireIfStale não pode expirar uma reserva já confirmada");
        }

        assertEquals("confirmed", eventWalletRepository.currentStatus(userId, eventId),
                "a confirmação concorrente deve permanecer intacta");
        assertEquals(4, eventCapRepository.findByEventId(eventId).getQuant(),
                "nenhuma devolução de vaga: quant continua capacity - 1");
    }

    /**
     * Fixa o filtro {@code status = 'pending'} de {@code findExpiredPending}: uma reserva
     * confirmada ANTES de vencer, mesmo com {@code expires_at} no passado, não é varrida
     * pelo job e não tem a vaga devolvida.
     */
    @Test
    public void confirmedReservationWithPastExpiresAtIsIgnoredByJob() throws Exception {
        User creator = new User();
        creator.setName("Exp Filter Creator");
        creator.setEmail("exp-filter-creator@example.com");
        creator.setFone("11955550002");
        creator = userService.createUser(creator);

        User u = new User();
        u.setName("Exp Filter User");
        u.setEmail("exp-filter-user@example.com");
        u.setFone("11966660002");
        u = userService.createUser(u);
        myWalletService.ensureExists(u.getId());

        Event e = new Event();
        e.setCreator_id(creator.getId());
        e.setEvent_name("Evento Expiracao Filtro");
        e.setIs_EAD(true);
        e.setEvent_date(LocalDateTime.now().plusDays(10));
        e.setLot_quantity(5);
        e.setDescription("desc");
        e = eventService.createEvent(e);

        long userId = u.getId();
        long eventId = e.getEvent_id();

        eventWalletService.addLink(new EventWallet(userId, eventId));
        eventWalletService.confirm(userId, eventId);
        assertEquals(4, eventCapRepository.findByEventId(eventId).getQuant());

        // expires_at no passado numa linha JÁ confirmed
        forcarExpiresAtNoPassado(userId, eventId);

        List<EventWallet> stale = eventWalletRepository.findExpiredPending();
        assertFalse(contem(stale, userId, eventId),
                "linha confirmed não pode entrar na varredura de pending vencidas");

        reservationExpiryService.expireStaleReservations();

        assertEquals("confirmed", eventWalletRepository.currentStatus(userId, eventId));
        assertEquals(4, eventCapRepository.findByEventId(eventId).getQuant(),
                "vaga não é devolvida para reserva confirmada");
    }

    // Força expires_at da reserva para o passado — mesmo UPDATE parametrizado (JDBC puro,
    // PreparedStatement, ?) do teste happy-path. Sem filtro de status: aplica-se também a
    // linhas já confirmed.
    private void forcarExpiresAtNoPassado(long userId, long eventId) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE walletevent SET expires_at = CURRENT_TIMESTAMP - interval '1 minute' "
               + "WHERE user_id = ? AND event_id = ?")) {
            ps.setLong(1, userId);
            ps.setLong(2, eventId);
            int linhas = ps.executeUpdate();
            assertEquals(1, linhas,
                    "forçar expires_at deve afetar exatamente a reserva do teste");
        }
    }

    private static boolean contem(List<EventWallet> reservas, long userId, long eventId) {
        return reservas.stream()
                .anyMatch(w -> w.getUserId() == userId && w.getEventId() == eventId);
    }
}
