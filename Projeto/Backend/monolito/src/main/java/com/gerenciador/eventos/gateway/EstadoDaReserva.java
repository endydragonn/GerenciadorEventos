package com.gerenciador.eventos.gateway;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gerenciador.eventos.admissionclient.api.AdmissionResult;
import com.gerenciador.eventos.admissionclient.api.AdmissionStatus;

/**
 * Corpo de estado da reserva em modo fila (issue #43): o do {@code 202} de {@code POST /bff/event-wallets} e o de
 * {@code GET /bff/event-wallets/admission}. {@code status}: {@code queued}, {@code pending} (com {@code expiresAt}), {@code rejected} (com o
 * {@code reason}) ou {@code timed_out}. Sem dado pessoal: só o estado, o evento e a chave que o próprio cliente conhece.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EstadoDaReserva(String status, Long eventId, String idempotencyKey, String expiresAt, String reason) {

    static EstadoDaReserva de(AdmissionResult r) {
        return new EstadoDaReserva(r.status(), r.eventId(), r.idempotencyKey(), r.expiresAt(), r.reason());
    }

    static EstadoDaReserva de(AdmissionStatus s, String idempotencyKey) {
        return new EstadoDaReserva(s.status(), s.eventId(), idempotencyKey, s.expiresAt(), s.reason());
    }
}
