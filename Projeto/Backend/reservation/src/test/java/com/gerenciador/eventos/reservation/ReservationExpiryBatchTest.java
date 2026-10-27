package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Expiração em lotes (issue #61): o job lê no máximo {@code reservation.expiry.batch-size} linhas por lote com
 * {@code FOR UPDATE SKIP LOCKED} na MESMA transação que expira/libera/appenda, drena o backlog em vários lotes e um
 * erro numa linha (savepoint) não derruba as outras. Postgres real; lote de 2 para exercitar a repetição.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
@TestPropertySource(properties = {"reservation.expiry.scan.ms=3600000", "reservation.expiry.batch-size=2"})
public class ReservationExpiryBatchTest extends ReservationTestSupport {
    private static final int CAPACIDADE = 20;

    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventWalletRepository eventWalletRepository;
    @Autowired private ReservationExpiryService reservationExpiryService;
    @Autowired private DatabaseConnection databaseConnection;
    @MockitoSpyBean private ReservationEvents reservationEvents;

    @BeforeEach
    @AfterEach
    public void limpar() {
        // zera o spy e esvazia qualquer pending vencida que outra classe tenha deixado no banco compartilhado
        reset(reservationEvents);
        reservationExpiryService.expireStaleReservations();
    }

    /** Cria {@code n} reservas pending vencidas no mesmo evento; a de índice 0 é a mais antiga. */
    private List<Long> reservasVencidas(long eventId, int n) throws Exception {
        List<Long> usuarios = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Long userId = novoUsuario("u").getId();
            eventWalletService.addLink(new EventWallet(userId, eventId));
            try (Connection conn = databaseConnection.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "UPDATE reservation.walletevent SET expires_at = CURRENT_TIMESTAMP - make_interval(mins => ?) "
                   + "WHERE user_id = ? AND event_id = ?")) {
                ps.setInt(1, 100 - i);
                ps.setLong(2, userId);
                ps.setLong(3, eventId);
                assertEquals(1, ps.executeUpdate());
            }
            usuarios.add(userId);
        }
        return usuarios;
    }

    private int quant(long eventId) {
        return eventCapRepository.findByEventId(eventId).getQuant();
    }

    private long eventosExpired(long eventId) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM reservation.event_store WHERE stream_type = 'reservation.wallet' "
               + "AND event_type = 'ReservationExpired' AND stream_id LIKE ?")) {
            ps.setString(1, "%:" + eventId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    public void backlogMaiorQueOLoteEDrenadoNumaExecucao() throws Exception {
        long eventId = novoEvento(null, "evento", CAPACIDADE).getEvent_id();
        List<Long> usuarios = reservasVencidas(eventId, 5);
        assertEquals(CAPACIDADE - 5, quant(eventId));

        reservationExpiryService.expireStaleReservations();

        for (Long u : usuarios) {
            assertEquals("expired", eventWalletRepository.currentStatus(u, eventId));
        }
        assertEquals(CAPACIDADE, quant(eventId), "cada vaga volta exatamente uma vez");
        assertEquals(5, eventosExpired(eventId), "um ReservationExpired por linha");
    }

    @Test
    public void linhaTravadaPorOutraTransacaoEPuladaENaoLiberaEmDobro() throws Exception {
        long eventId = novoEvento(null, "evento", CAPACIDADE).getEvent_id();
        List<Long> usuarios = reservasVencidas(eventId, 5);

        // outra transação segura as 2 mais antigas (as que o ORDER BY pegaria primeiro)
        try (Connection outra = databaseConnection.getConnection()) {
            outra.setAutoCommit(false);
            try (PreparedStatement ps = outra.prepareStatement(
                    "SELECT user_id FROM reservation.walletevent WHERE event_id = ? AND user_id IN (?, ?) FOR UPDATE")) {
                ps.setLong(1, eventId);
                ps.setLong(2, usuarios.get(0));
                ps.setLong(3, usuarios.get(1));
                ps.executeQuery().close();
            }

            reservationExpiryService.expireStaleReservations();

            assertEquals("pending", eventWalletRepository.currentStatus(usuarios.get(0), eventId));
            assertEquals("pending", eventWalletRepository.currentStatus(usuarios.get(1), eventId));
            for (Long u : usuarios.subList(2, 5)) {
                assertEquals("expired", eventWalletRepository.currentStatus(u, eventId));
            }
            assertEquals(CAPACIDADE - 2, quant(eventId), "só as 3 linhas livres devolveram vaga");
            assertEquals(3, eventosExpired(eventId));

            outra.commit();
        }

        reservationExpiryService.expireStaleReservations();
        // uma 3ª execução não pode liberar nada de novo
        reservationExpiryService.expireStaleReservations();

        for (Long u : usuarios) {
            assertEquals("expired", eventWalletRepository.currentStatus(u, eventId));
        }
        assertEquals(CAPACIDADE, quant(eventId), "nenhuma vaga liberada em dobro");
        assertEquals(5, eventosExpired(eventId), "um ReservationExpired por linha, nenhum repetido");
    }

    @Test
    public void erroNumaLinhaNaoParaAsOutrasEDesfazSoAquelaLinha() throws Exception {
        long eventId = novoEvento(null, "evento", CAPACIDADE).getEvent_id();
        List<Long> usuarios = reservasVencidas(eventId, 5);
        Long ruim = usuarios.get(0); // a mais antiga: sempre a primeira de cada lote
        doThrow(new IllegalStateException("falha forçada")).when(reservationEvents)
                .expired(any(Connection.class), eq(ruim), eq(eventId), anyInt());

        reservationExpiryService.expireStaleReservations();

        assertEquals("pending", eventWalletRepository.currentStatus(ruim, eventId),
                "a linha que falhou volta ao estado anterior (savepoint)");
        for (Long u : usuarios.subList(1, 5)) {
            assertEquals("expired", eventWalletRepository.currentStatus(u, eventId));
        }
        assertEquals(CAPACIDADE - 1, quant(eventId), "a vaga da linha que falhou não foi liberada");
        assertEquals(4, eventosExpired(eventId));
    }

    @Test
    public void loteSemProgressoTermina() throws Exception {
        long eventId = novoEvento(null, "evento", CAPACIDADE).getEvent_id();
        List<Long> usuarios = reservasVencidas(eventId, 2); // lote cheio (2) em que nada expira
        doThrow(new IllegalStateException("falha forçada")).when(reservationEvents)
                .expired(any(Connection.class), any(), eq(eventId), anyInt());

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> reservationExpiryService.expireStaleReservations());

        for (Long u : usuarios) {
            assertEquals("pending", eventWalletRepository.currentStatus(u, eventId));
        }
        assertEquals(CAPACIDADE - 2, quant(eventId));
        assertEquals(0, eventosExpired(eventId));
    }
}
