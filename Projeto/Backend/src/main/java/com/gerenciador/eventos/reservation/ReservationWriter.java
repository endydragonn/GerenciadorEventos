package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;

/**
 * Fronteira interna "gravar reserva" (lado {@code wallet} do split futuro #47). Opera na
 * {@link Connection}/transação do chamador, junto de {@link CapacityReserver}. Implementação:
 * {@link EventWalletRepository}.
 */
interface ReservationWriter {

    /**
     * Cria (ou revive expired/cancelled) a reserva {@code pending}.
     * @return a {@code version} do stream após a transição (vem da própria guarda); {@code 0} se já havia uma ativa
     */
    int reservePending(Connection conn, Long userId, Long eventId, LocalDateTime expiresAt)
            throws SQLException;
}
