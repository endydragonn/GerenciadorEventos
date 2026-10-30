package com.gerenciador.eventos.admissionclient.api;

/**
 * Corpo de {@code POST /internal/admissions} (issue #41): a tentativa de reserva que o gateway registra no contador do evento.
 * {@code path} é o braço da reserva ({@code cas} ou {@code naive}); {@code idempotencyKey} é a chave que o gateway vai usar na
 * reserva (a do cliente, ou a que o próprio gateway gerou).
 */
public record AdmissionRequest(Long eventId, Long userId, String idempotencyKey, String path) { }
