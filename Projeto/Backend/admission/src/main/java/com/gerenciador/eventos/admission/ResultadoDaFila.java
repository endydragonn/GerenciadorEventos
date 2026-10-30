package com.gerenciador.eventos.admission;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gerenciador.eventos.admissionclient.api.AdmissionStatus;

/**
 * O que o Redis guarda em {@code adm:res:<userId>:<chave>} (issue #42): o estado e a tentativa a que ele pertence. Sem dado pessoal.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultadoDaFila(String status, Long eventId, String attemptId, Long deadline, String expiresAt, String reason) {

    /** Carência além do deadline: um {@code queued} mais velho que isso já perdeu a mensagem e vale como {@code timed_out}. */
    static final long CARENCIA_MS = 60_000;

    static ResultadoDaFila queued(AdmissionMessage m) {
        return new ResultadoDaFila(AdmissionStatus.QUEUED, m.eventId(), m.attemptId(), m.deadline(), null, null);
    }

    ResultadoDaFila pending(String expiracao) {
        return como(AdmissionStatus.PENDING, expiracao, null);
    }

    ResultadoDaFila rejected(String motivo) {
        return como(AdmissionStatus.REJECTED, null, motivo);
    }

    ResultadoDaFila timedOut() {
        return como(AdmissionStatus.TIMED_OUT, null, null);
    }

    private ResultadoDaFila como(String novo, String expiracao, String motivo) {
        return new ResultadoDaFila(novo, eventId, attemptId, deadline, expiracao, motivo);
    }

    /** O estado como o cliente deve ver: um {@code queued} vencido (deadline + carência) vale como {@code timed_out}. */
    ResultadoDaFila efetivo(long agora) {
        boolean vencido = AdmissionStatus.QUEUED.equals(status) && deadline != null && deadline + CARENCIA_MS < agora;
        return vencido ? timedOut() : this;
    }

    AdmissionStatus paraStatus() {
        return new AdmissionStatus(status, eventId, expiresAt, reason);
    }
}
