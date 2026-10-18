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

    /** Cria (ou revive expired/cancelled) a reserva {@code pending}; {@code false} se já havia uma ativa. */
    boolean reservePending(Connection conn, Long userId, Long eventId, LocalDateTime expiresAt)
            throws SQLException;
}
