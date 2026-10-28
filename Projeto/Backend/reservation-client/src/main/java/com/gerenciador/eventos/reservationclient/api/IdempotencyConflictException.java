package com.gerenciador.eventos.reservationclient.api;

/**
 * O mesmo usuário reusou a {@code Idempotency-Key} com outro evento do que na reserva original
 * (issue #24; a chave tem escopo por usuário desde a #129). Não estende {@link IllegalArgumentException} de propósito: no HTTP vira 422, não 400.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
