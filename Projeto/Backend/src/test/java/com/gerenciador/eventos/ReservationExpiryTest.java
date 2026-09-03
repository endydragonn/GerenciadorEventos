package com.gerenciador.eventos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

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
}
