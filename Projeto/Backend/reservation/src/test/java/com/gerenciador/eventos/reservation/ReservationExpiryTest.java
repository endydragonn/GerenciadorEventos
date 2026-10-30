package com.gerenciador.eventos.reservation;

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

import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.reservation.EventWalletRepository;
import com.gerenciador.eventos.reservation.EventWalletService;
import com.gerenciador.eventos.reservation.ReservationExpiryService;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
// Estica o intervalo do @Scheduled de ReservationExpiryService para 1h (initialDelay e
// fixedDelay), bem além da duração da suíte, para que o job nunca dispare sozinho em
// background durante os testes; as chamadas explícitas a expireStaleReservations() seguem
// iguais.
@TestPropertySource(properties = "reservation.expiry.scan.ms=3600000")
public class ReservationExpiryTest extends ReservationTestSupport {
    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventWalletRepository eventWalletRepository;
    @Autowired private ReservationExpiryService reservationExpiryService;
    @Autowired private DatabaseConnection databaseConnection;

    @Test
    public void staleReservationIsExpiredAndSeatReturned() throws Exception {
        Usuario u = novoUsuario("u");

        Evento e = novoEvento(null, "evento", 3);

        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        assertEquals(2, capacidade(e.getEvent_id()).getQuant());

        // força expires_at para o passado
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE reservation.walletevent SET expires_at = CURRENT_TIMESTAMP - interval '1 minute' "
               + "WHERE user_id = ? AND event_id = ?")) {
            ps.setLong(1, u.getId());
            ps.setLong(2, e.getEvent_id());
            ps.executeUpdate();
        }

        reservationExpiryService.expireStaleReservations();

        assertEquals("expired", eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()));
        assertEquals(3, capacidade(e.getEvent_id()).getQuant(),
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
        Usuario u = novoUsuario("u");

        Evento e = novoEvento(null, "evento", 5);

        long userId = u.getId();
        long eventId = e.getEvent_id();

        eventWalletService.addLink(new EventWallet(userId, eventId));
        assertEquals(4, capacidade(eventId).getQuant(),
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
            assertEquals(0, eventWalletRepository.expireIfStale(conn, userId, eventId).versao(),
                    "expireIfStale não pode expirar uma reserva já confirmada");
        }

        assertEquals("confirmed", eventWalletRepository.currentStatus(userId, eventId),
                "a confirmação concorrente deve permanecer intacta");
        assertEquals(4, capacidade(eventId).getQuant(),
                "nenhuma devolução de vaga: quant continua capacity - 1");
    }

    /**
     * Fixa o filtro {@code status = 'pending'} de {@code findExpiredPending}: uma reserva
     * confirmada ANTES de vencer, mesmo com {@code expires_at} no passado, não é varrida
     * pelo job e não tem a vaga devolvida.
     */
    @Test
    public void confirmedReservationWithPastExpiresAtIsIgnoredByJob() throws Exception {
        Usuario u = novoUsuario("u");

        Evento e = novoEvento(null, "evento", 5);

        long userId = u.getId();
        long eventId = e.getEvent_id();

        eventWalletService.addLink(new EventWallet(userId, eventId));
        eventWalletService.confirm(userId, eventId);
        assertEquals(4, capacidade(eventId).getQuant());

        // expires_at no passado numa linha JÁ confirmed
        forcarExpiresAtNoPassado(userId, eventId);

        List<EventWallet> stale = eventWalletRepository.findExpiredPending();
        assertFalse(contem(stale, userId, eventId),
                "linha confirmed não pode entrar na varredura de pending vencidas");

        reservationExpiryService.expireStaleReservations();

        assertEquals("confirmed", eventWalletRepository.currentStatus(userId, eventId));
        assertEquals(4, capacidade(eventId).getQuant(),
                "vaga não é devolvida para reserva confirmada");
    }

    // Força expires_at da reserva para o passado — mesmo UPDATE parametrizado (JDBC puro,
    // PreparedStatement, ?) do teste happy-path. Sem filtro de status: aplica-se também a
    // linhas já confirmed.
    private void forcarExpiresAtNoPassado(long userId, long eventId) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE reservation.walletevent SET expires_at = CURRENT_TIMESTAMP - interval '1 minute' "
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
