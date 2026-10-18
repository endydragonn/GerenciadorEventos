package com.gerenciador.eventos.reservation.api;

import java.util.List;

/** Interface pública da inscrição/reserva em eventos (módulo {@code reservation}). */
public interface EventWalletApi {

    EventWallet addLink(EventWallet ew);

    EventWallet confirm(Long userId, Long eventId);

    void removeLink(Long userId, Long eventId);

    List<EventWallet> listByUser(Long userId);

    String getValidationErrors(EventWallet ew);
}
