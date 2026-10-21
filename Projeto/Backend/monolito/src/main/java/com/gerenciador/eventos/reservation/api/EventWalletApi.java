package com.gerenciador.eventos.reservation.api;

import java.util.List;

/** Interface pública da inscrição/reserva em eventos (módulo {@code reservation}). */
public interface EventWalletApi {

    /** Reserva com {@code Idempotency-Key} gerada no servidor (devolvida em {@code getIdempotencyKey()}). */
    EventWallet addLink(EventWallet ew);

    /**
     * Reserva idempotente: a repetição com a mesma chave (do mesmo usuário, para o mesmo evento) devolve o
     * resultado da primeira sem tocar a capacidade; o mesmo usuário com outro evento ⇒
     * {@link IdempotencyConflictException}. A chave tem escopo por usuário (issue #129).
     * Chave {@code null}/em branco ⇒ o servidor gera uma.
     */
    EventWallet addLink(EventWallet ew, String idempotencyKey);

    EventWallet confirm(Long userId, Long eventId);

    void removeLink(Long userId, Long eventId);

    List<EventWallet> listByUser(Long userId);

    String getValidationErrors(EventWallet ew);
}
