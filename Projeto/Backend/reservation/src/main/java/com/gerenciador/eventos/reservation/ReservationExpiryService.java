package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Job que devolve a vaga de reservas {@code pending} que não foram confirmadas até
 * {@code expires_at}. Roda periodicamente e trabalha em <b>lotes</b> de até {@code reservation.expiry.batch-size}
 * linhas: cada lote é UMA transação que seleciona as vencidas com {@code FOR UPDATE SKIP LOCKED} (duas execuções
 * concorrentes não pegam a mesma linha) e, linha a linha, faz a marcação condicional ({@code expireIfStale}, de onde
 * sai a versão do evento e o {@code hold_id}) + appenda {@code ReservationExpired} com o {@code holdId}; a vaga NÃO é devolvida aqui
 * (#47): o consumer do stock a devolve ao consumir o evento, então o lote não segura a linha quente do {@code eventcap} nem fala com o stock. Cada linha roda
 * num savepoint: o erro numa linha desfaz só aquela linha e as outras do lote seguem. O job repete lotes até um voltar
 * incompleto ou sem nenhuma linha expirada (uma linha que falha sempre continua pending/vencida e seria
 * reselecionada para sempre).
 */
@Service
public class ReservationExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryService.class);

    @Autowired
    private EventWalletRepository eventWalletRepository;
    @Autowired
    private ReservationEvents reservationEvents;
    @Autowired
    private DatabaseConnection databaseConnection;

    @Value("${reservation.expiry.batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${reservation.expiry.scan.ms:30000}",
               initialDelayString = "${reservation.expiry.scan.ms:30000}")
    public void expireStaleReservations() {
        int limite = Math.max(1, batchSize);
        int released = 0;
        try {
            while (true) {
                int[] lote = expireBatch(limite); // {selecionadas, expiradas}
                released += lote[1];
                if (lote[0] < limite || lote[1] == 0) break;
            }
        } catch (SQLException | RuntimeException ex) {
            log.warn("[ReservationExpiry] falha ao processar um lote de expiração: {}", ex.getMessage());
        }
        if (released > 0) {
            log.info("[ReservationExpiry] {} reserva(s) expirada(s) (a vaga volta pelo consumer do stock)", released);
        }
    }

    /** Um lote = uma transação. @return {selecionadas, expiradas}. */
    private int[] expireBatch(int limite) throws SQLException {
        try (Connection conn = databaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<EventWallet> lote = eventWalletRepository.findExpiredPendingForUpdate(conn, limite);
                int expiradas = 0;
                for (EventWallet ew : lote) {
                    if (expireOne(conn, ew)) expiradas++;
                }
                conn.commit();
                return new int[] {lote.size(), expiradas};
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** Uma linha do lote, num savepoint: falha desfaz só ela (no Postgres o 1º erro aborta a transação inteira). */
    private boolean expireOne(Connection conn, EventWallet ew) throws SQLException {
        Savepoint sp = conn.setSavepoint();
        try {
            EventWalletRepository.Transicao t = eventWalletRepository.expireIfStale(conn, ew.getUserId(), ew.getEventId());
            boolean expired = t.aplicada();
            if (expired) {
                reservationEvents.expired(conn, ew.getUserId(), ew.getEventId(), t.versao(), t.holdId());
            }
            conn.releaseSavepoint(sp);
            return expired;
        } catch (SQLException | RuntimeException ex) {
            log.warn("[ReservationExpiry] falha ao expirar reserva user={} event={}: {}",
                    ew.getUserId(), ew.getEventId(), ex.getMessage());
            conn.rollback(sp);
            return false;
        }
    }
}
