package com.gerenciador.eventos.stockclient.api;

import java.util.UUID;

/**
 * Corpo de {@code POST /internal/holds}: uma tentativa de reserva de vaga. {@code holdId} é a chave de idempotência do passo
 * (gerada pelo wallet); {@code mode} é {@value #CAS} (debita o {@code eventcap}) ou {@value #NAIVE} (só registra o hold).
 */
public record HoldRequest(UUID holdId, Long userId, Long eventId, String mode) {

    public static final String CAS = "cas";
    public static final String NAIVE = "naive";
}
