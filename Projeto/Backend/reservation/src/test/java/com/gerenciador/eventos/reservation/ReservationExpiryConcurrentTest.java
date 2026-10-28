package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Expiração com 2+ réplicas (issue #111): N execuções de {@code expireStaleReservations()} AO MESMO TEMPO (cada uma na sua
 * conexão, como se fossem N instâncias do serviço) sobre um backlog bem maior que o lote. Cada reserva vencida tem de
 * expirar exatamente uma vez: vaga devolvida uma só vez, um único {@code ReservationExpired} por stream, nada de
 * {@code pending} vencida sobrando. As reservas ainda vivas ({@code pending} dentro do prazo) funcionam de prova contra
 * devolução em dobro: com elas segurando vagas, {@code quant} não bate no teto {@code capacity} (onde o
 * {@code release} é no-op) e um excesso apareceria. Postgres real; lote de 3 para o backlog virar muitos lotes e as
 * réplicas disputarem linhas de verdade.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
@TestPropertySource(properties = {"reservation.expiry.scan.ms=3600000", "reservation.expiry.batch-size=3"})
public class ReservationExpiryConcurrentTest extends ReservationTestSupport {
    private static final int CAPACIDADE = 60;
    private static final int REPLICAS = 4;
    private static final int VENCIDAS_A = 17;
    private static final int VENCIDAS_B = 11;
    private static final int VIVAS_A = 4;

    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventWalletRepository eventWalletRepository;
    @Autowired private ReservationExpiryService reservationExpiryService;
    @Autowired private DatabaseConnection databaseConnection;

    @BeforeEach
    public void limpar() {
        // esvazia qualquer pending vencida que outra classe tenha deixado no banco compartilhado
        reservationExpiryService.expireStaleReservations();
    }

    /** {@code n} reservas pending de {@code eventId}; vencidas (expires_at no passado) ou ainda dentro do prazo. */
    private List<Long> reservas(long eventId, int n, boolean vencidas) throws Exception {
        List<Long> usuarios = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Long userId = novoUsuario("u").getId();
            eventWalletService.addLink(new EventWallet(userId, eventId));
            if (vencidas) {
                try (Connection conn = databaseConnection.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                         "UPDATE reservation.walletevent SET expires_at = CURRENT_TIMESTAMP - make_interval(mins => ?) "
                       + "WHERE user_id = ? AND event_id = ?")) {
                    ps.setInt(1, 100 - i);
                    ps.setLong(2, userId);
                    ps.setLong(3, eventId);
                    assertEquals(1, ps.executeUpdate());
                }
            }
            usuarios.add(userId);
        }
        return usuarios;
    }

    private int quant(long eventId) {
        return eventCapRepository.findByEventId(eventId).getQuant();
    }

    private long contar(String sql, long eventId) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long expirados(long eventId) throws Exception {
        return contar("SELECT count(*) FROM reservation.event_store WHERE stream_type = 'reservation.wallet' "
                + "AND event_type = 'ReservationExpired' AND stream_id LIKE '%:' || ?::text", eventId);
    }

    private long streamsComExpiradoRepetido(long eventId) throws Exception {
        return contar("SELECT count(*) FROM (SELECT stream_id FROM reservation.event_store "
                + "WHERE stream_type = 'reservation.wallet' AND event_type = 'ReservationExpired' "
                + "AND stream_id LIKE '%:' || ?::text GROUP BY stream_id HAVING count(*) > 1) t", eventId);
    }

    private long vencidasPendentes(long eventId) throws Exception {
        return contar("SELECT count(*) FROM reservation.walletevent WHERE status = 'pending' "
                + "AND expires_at < CURRENT_TIMESTAMP AND event_id = ?", eventId);
    }

    @RepeatedTest(5)
    public void reservasVencidasExpiramExatamenteUmaVezComVariasReplicasAoMesmoTempo() throws Exception {
        long eventoA = novoEvento(null, "evento A", CAPACIDADE).getEvent_id();
        long eventoB = novoEvento(null, "evento B", CAPACIDADE).getEvent_id();
        List<Long> vencidasA = reservas(eventoA, VENCIDAS_A, true);
        List<Long> vivasA = reservas(eventoA, VIVAS_A, false);
        List<Long> vencidasB = reservas(eventoB, VENCIDAS_B, true);

        Concorrencia.emParalelo(REPLICAS, () -> {
            reservationExpiryService.expireStaleReservations();
            return null;
        });

        for (Long u : vencidasA) {
            assertEquals("expired", eventWalletRepository.currentStatus(u, eventoA));
        }
        for (Long u : vencidasB) {
            assertEquals("expired", eventWalletRepository.currentStatus(u, eventoB));
        }
        for (Long u : vivasA) {
            assertEquals("pending", eventWalletRepository.currentStatus(u, eventoA), "reserva viva não expira");
        }
        assertEquals(0, vencidasPendentes(eventoA) + vencidasPendentes(eventoB), "nenhuma vencida sobrando pending");
        assertEquals(CAPACIDADE - VIVAS_A, quant(eventoA), "cada vaga volta uma única vez (as vivas seguem seguras)");
        assertEquals(CAPACIDADE, quant(eventoB), "cada vaga volta uma única vez");
        assertEquals(VENCIDAS_A, expirados(eventoA), "um ReservationExpired por reserva vencida");
        assertEquals(VENCIDAS_B, expirados(eventoB), "um ReservationExpired por reserva vencida");
        assertEquals(0, streamsComExpiradoRepetido(eventoA) + streamsComExpiradoRepetido(eventoB),
                "nenhum stream com ReservationExpired repetido");
    }
}
