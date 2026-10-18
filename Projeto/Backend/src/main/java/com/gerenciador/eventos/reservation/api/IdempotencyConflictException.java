package com.gerenciador.eventos.reservation.api;

/**
 * A mesma {@code Idempotency-Key} foi usada com outro usuário/evento do que na reserva original
 * (issue #24). Não estende {@link IllegalArgumentException} de propósito: no HTTP vira 422, não 400.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
