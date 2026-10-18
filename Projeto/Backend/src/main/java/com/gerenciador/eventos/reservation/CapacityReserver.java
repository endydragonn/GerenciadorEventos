package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Fronteira interna "decrementar capacidade" (lado {@code stock} do split futuro #47). Opera na
 * {@link Connection}/transação do chamador: {@link EventWalletService#addLink} orquestra esta e
 * {@link ReservationWriter} na MESMA transação JDBC. Implementação: {@link EventCapRepository}.
 */
interface CapacityReserver {

    /** @return {@code true} se havia vaga e foi debitada; {@code false} se lotado. */
    boolean tryReserve(Connection conn, Long eventId) throws SQLException;
}
