package com.gerenciador.eventos.admissionclient.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Resultado consultável de uma mensagem da fila de admissão (issue #42), corpo de
 * {@code GET /internal/admissions/result?userId=&idempotencyKey=} (200; 404 sem corpo quando não há resultado).
 * {@code status}: {@value #QUEUED} (publicada, ainda sem resposta do reservation), {@value #PENDING} (reserva feita, com
 * {@code expiresAt} da confirmação, o {@code walletevent} em {@code pending}), {@value #REJECTED} (recusa de negócio do reservation, com
 * o {@code reason}) ou {@value #TIMED_OUT} (morreu na fila pelo TTL/dead-letter, ou ficou {@code queued} além do {@code deadline};
 * nome distinto do {@code expired} do {@code walletevent}, que é o {@code pending} vencido pelo job). Sem dado pessoal.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdmissionStatus(String status, Long eventId, String expiresAt, String reason) {

    public static final String QUEUED = "queued";
    public static final String PENDING = "pending";
    public static final String REJECTED = "rejected";
    public static final String TIMED_OUT = "timed_out";
}
