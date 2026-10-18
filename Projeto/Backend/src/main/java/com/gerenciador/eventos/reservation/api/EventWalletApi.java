package com.gerenciador.eventos.reservation.api;

import java.util.List;

/** Interface pública da inscrição/reserva em eventos (módulo {@code reservation}). */
public interface EventWalletApi {

    /** Reserva com {@code Idempotency-Key} gerada no servidor (devolvida em {@code getIdempotencyKey()}). */
    EventWallet addLink(EventWallet ew);

    /**
     * Reserva idempotente: a repetição com a mesma chave (e o mesmo usuário/evento) devolve o resultado
     * da primeira sem tocar a capacidade; outro usuário/evento ⇒ {@link IdempotencyConflictException}.
     * Chave {@code null}/em branco ⇒ o servidor gera uma.
     */
    EventWallet addLink(EventWallet ew, String idempotencyKey);

    EventWallet confirm(Long userId, Long eventId);

    void removeLink(Long userId, Long eventId);

    List<EventWallet> listByUser(Long userId);

    String getValidationErrors(EventWallet ew);
}
