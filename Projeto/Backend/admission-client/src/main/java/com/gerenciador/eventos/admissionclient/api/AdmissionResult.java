package com.gerenciador.eventos.admissionclient.api;

/**
 * Resposta de {@code POST /internal/admissions}: o modo em que a reserva segue e o RPM atual do evento. Na #41 o modo é sempre
 * {@value #SYNC} (a reserva segue pelo caminho síncrono de hoje). {@code rpm} é só sinal de carga (nada de capacidade é decidido
 * por ele); {@code -1} = desconhecido (o admission não conseguiu contar, ou o cliente fez fail-open).
 */
public record AdmissionResult(String mode, long rpm) {

    public static final String SYNC = "sync";

    /** Segue síncrono; {@code rpm} {@code -1} quando não há contagem. */
    public static AdmissionResult sync(long rpm) {
        return new AdmissionResult(SYNC, rpm);
    }
}
