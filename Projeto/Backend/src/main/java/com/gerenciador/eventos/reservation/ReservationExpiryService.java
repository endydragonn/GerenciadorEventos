package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.gerenciador.eventos.reservation.api.EventWallet;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Job que devolve a vaga de reservas {@code pending} que não foram confirmadas até
 * {@code expires_at}. Roda periodicamente; cada reserva é tratada numa transação própria
 * (libera a vaga em {@code eventcap} + marca {@code expired} + appenda {@code ReservationExpired}), e a marcação é
 * condicional ({@code expireIfStale}, de onde sai a versão do evento) para não expirar algo que o usuário acabou de
 * confirmar: só emite o evento por linha que a guarda de fato expirou.
 */
@Service
public class ReservationExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryService.class);

    @Autowired
    private EventWalletRepository eventWalletRepository;
    @Autowired
    private EventCapRepository eventCapRepository;
    @Autowired
    private ReservationEvents reservationEvents;
    @Autowired
    private DatabaseConnection databaseConnection;

    @Scheduled(fixedDelayString = "${reservation.expiry.scan.ms:30000}",
               initialDelayString = "${reservation.expiry.scan.ms:30000}")
    public void expireStaleReservations() {
        List<EventWallet> stale = eventWalletRepository.findExpiredPending();
        if (stale.isEmpty()) return;

        int released = 0;
        for (EventWallet ew : stale) {
            if (expireOne(ew)) released++;
        }
        if (released > 0) {
            log.info("[ReservationExpiry] {} reserva(s) expirada(s), vagas devolvidas", released);
        }
    }

    private boolean expireOne(EventWallet ew) {
        try (Connection conn = databaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int versao = eventWalletRepository.expireIfStale(conn, ew.getUserId(), ew.getEventId());
                boolean expired = versao > 0;
                if (expired) {
                    eventCapRepository.release(conn, ew.getEventId());
                    reservationEvents.expired(conn, ew.getUserId(), ew.getEventId(), versao);
                }
                conn.commit();
                return expired;
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException | RuntimeException ex) {
            log.warn("[ReservationExpiry] falha ao expirar reserva user={} event={}: {}",
                    ew.getUserId(), ew.getEventId(), ex.getMessage());
            return false;
        }
    }
}
