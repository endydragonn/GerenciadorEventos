package com.gerenciador.eventos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.POJO.Event;
import com.gerenciador.eventos.POJO.EventWallet;
import com.gerenciador.eventos.POJO.User;
import com.gerenciador.eventos.Repository.EventAvailabilityRepository;
import com.gerenciador.eventos.Service.EventService;
import com.gerenciador.eventos.Service.EventWalletService;
import com.gerenciador.eventos.Service.MyWalletService;
import com.gerenciador.eventos.Service.ReadModelRefreshService;
import com.gerenciador.eventos.Service.UserService;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.config.DatabaseConnection;

/**
 * Trava em teste o job {@link ReadModelRefreshService} + o repository
 * {@code EventAvailabilityRepository}: o read model defasado {@code event_availability} é
 * recalculado a partir do estado real de {@code eventcap}/{@code walletevent}
 * ({@code available = capacity - reservas ativas}, piso 0; {@code -1} = ilimitado).
 *
 * <p>Contexto default (feature flag {@code reservation.readpath=cas}) — as reservas são
 * feitas pelo caminho CAS normal; aqui só se observa o read model derivado delas.</p>
 *
 * <p>{@code readmodel.availability.refresh.ms=3600000}: o job {@link ReadModelRefreshService}
 * nunca dispara sozinho durante a suíte (os testes chamam {@code refresh()} na mão). Isto
 * torna a classe robusta mesmo rodada por {@code mvn test} / IDE, sem o reforço equivalente
 * que {@code run-tests.sh} injeta por env var.</p>
 */
@SpringBootTest(properties = "readmodel.availability.refresh.ms=3600000")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class EventAvailabilityRefreshTest {

    @Autowired private UserService userService;
    @Autowired private MyWalletService myWalletService;
    @Autowired private EventService eventService;
    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventAvailabilityRepository eventAvailabilityRepository;
    @Autowired private ReadModelRefreshService readModelRefreshService;
    @Autowired private DatabaseConnection databaseConnection;

    @Test
    public void refreshCalculaDisponibilidadeComoCapacidadeMenosReservasAtivas() {
        User creator = novoUsuario("rm-refresh-creator@example.com", "rm-fone-01");
        Event e = novoEvento(creator.getId(), "RM Refresh Cap5", 5);

        for (int i = 0; i < 2; i++) {
            User u = novoUsuario("rm-refresh-u" + i + "@example.com", "rm-fone-1" + i);
            eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
            eventWalletService.confirm(u.getId(), e.getEvent_id());
        }

        readModelRefreshService.refresh();

        assertEquals(3, eventAvailabilityRepository.findAvailable(e.getEvent_id()),
                "available deve ser capacity 5 - 2 reservas ativas");
    }

    @Test
    public void refreshEspelhaSentinelaDeEventoIlimitado() {
        User creator = novoUsuario("rm-ilim-creator@example.com", "rm-fone-02");
        Event e = novoEvento(creator.getId(), "RM Refresh Ilimitado", -1);

        // linha ainda não calculada antes do primeiro refresh
        assertNull(eventAvailabilityRepository.findAvailable(e.getEvent_id()));

        readModelRefreshService.refresh();

        assertEquals(-1, eventAvailabilityRepository.findAvailable(e.getEvent_id()),
                "evento ilimitado (capacity -1) => available -1");
    }

    @Test
    public void refreshedAtERecenteEAvancaEntreRefreshes() throws Exception {
        User creator = novoUsuario("rm-ts-creator@example.com", "rm-fone-03");
        Event e = novoEvento(creator.getId(), "RM Refresh Timestamp", 3);

        readModelRefreshService.refresh();
        LocalDateTime t1 = lerRefreshedAt(e.getEvent_id());
        assertNotNull(t1, "refreshed_at não pode ser nulo após um refresh");
        assertTrue(Math.abs(ChronoUnit.SECONDS.between(t1, LocalDateTime.now())) < 120,
                "refreshed_at deve ser recente (menos de 120s de agora), veio: " + t1);

        readModelRefreshService.refresh();
        LocalDateTime t2 = lerRefreshedAt(e.getEvent_id());
        assertTrue(!t2.isBefore(t1), "refreshed_at não pode retroceder entre refreshes");
    }

    @Test
    public void cancelamentoDevolveDisponibilidadeNoProximoRefresh() {
        User creator = novoUsuario("rm-cancel-creator@example.com", "rm-fone-04");
        Event e = novoEvento(creator.getId(), "RM Refresh Cancel", 2);
        User u = novoUsuario("rm-cancel-u@example.com", "rm-fone-05");

        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        eventWalletService.confirm(u.getId(), e.getEvent_id());
        readModelRefreshService.refresh();
        assertEquals(1, eventAvailabilityRepository.findAvailable(e.getEvent_id()),
                "1 reserva ativa em capacity 2 => available 1");

        eventWalletService.removeLink(u.getId(), e.getEvent_id());
        readModelRefreshService.refresh();
        assertEquals(2, eventAvailabilityRepository.findAvailable(e.getEvent_id()),
                "após cancelar, a vaga volta ao read model no próximo refresh");
    }

    // ===================== helpers =====================

    private User novoUsuario(String email, String fone) {
        User u = new User();
        u.setName("RM " + email);
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

    private LocalDateTime lerRefreshedAt(Long eventId) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT refreshed_at FROM event_availability WHERE event_id = ?")) {
            ps.setLong(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Timestamp ts = rs.getTimestamp("refreshed_at");
                return ts == null ? null : ts.toLocalDateTime();
            }
        }
    }
}
