package com.gerenciador.eventos.admission;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Corpo da mensagem da fila de admissão (issue #42): só ids e a chave, sem dado pessoal. {@code attemptId} (UUID por publicação) amarra a
 * mensagem à espera e ao resultado: uma reentrega de uma tentativa antiga nunca mexe na tentativa nova da mesma chave. {@code deadline} =
 * epoch ms da decisão + TTL da fila.
 */
public record AdmissionMessage(Long userId, Long eventId, String idempotencyKey, String attemptId, long deadline) {

    /** Membro das esperas ({@code adm:wait:<eventId>} e {@code adm:uwait:<userId>}), score = {@code deadline}. */
    @JsonIgnore
    public String membro() {
        return userId + ":" + idempotencyKey + ":" + attemptId;
    }
}
