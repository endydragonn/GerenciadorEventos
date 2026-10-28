package com.gerenciador.eventos.admissionclient.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Resposta de {@code POST /internal/admissions}: o modo em que a reserva segue e o RPM atual do evento. {@value #SYNC} = a reserva segue pelo
 * caminho síncrono de sempre; {@value #QUEUED} (issue #43) = o evento está em modo fila: a tentativa já foi enfileirada (ou é a repetição de
 * uma chave que já tem estado) e os demais campos trazem o estado dela ({@code status}: queued ou pending; {@code expiresAt} no pending).
 * {@code rpm} é só sinal de carga (nada de capacidade é decidido por ele); {@code -1} = desconhecido (o admission não conseguiu contar, ou o
 * cliente fez fail-open). Os campos de estado só existem em {@code queued} (ausentes no JSON de {@code sync}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdmissionResult(String mode, long rpm, String status, String idempotencyKey, Long eventId, String expiresAt, String reason) {

    public static final String SYNC = "sync";
    public static final String QUEUED = "queued";

    /** Segue síncrono; {@code rpm} {@code -1} quando não há contagem. */
    public static AdmissionResult sync(long rpm) {
        return new AdmissionResult(SYNC, rpm, null, null, null, null, null);
    }

    /** A tentativa está na fila (ou é a repetição de uma chave com estado): {@code estado} é o estado público dela. */
    public static AdmissionResult queued(long rpm, AdmissionStatus estado, String idempotencyKey) {
        return new AdmissionResult(QUEUED, rpm, estado.status(), idempotencyKey, estado.eventId(), estado.expiresAt(), estado.reason());
    }

    @JsonIgnore
    public boolean queued() {
        return QUEUED.equals(mode);
    }
}
