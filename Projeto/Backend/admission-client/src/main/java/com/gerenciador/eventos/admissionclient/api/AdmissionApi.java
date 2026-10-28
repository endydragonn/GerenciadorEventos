package com.gerenciador.eventos.admissionclient.api;

import java.util.Optional;

/**
 * Contrato do serviço admission visto pelo gateway. {@link #admit} conta a tentativa de reserva no contador do evento e devolve o modo
 * (issue #41; na #43 o modo pode ser {@code queued}). Falha do serviço (indisponível, lento, token errado, pedido que ele recusa com 400)
 * devolve {@code sync} (fail-open) e a reserva segue pelo caminho de hoje; a recusa de negócio (422/429) é a
 * {@link AdmissionRecusadaException}. {@link #result} (#42/#43) consulta o estado de uma tentativa da fila e, sem fail-open, lança
 * {@link AdmissionUnavailableException} se o serviço não responde.
 */
public interface AdmissionApi {

    AdmissionResult admit(AdmissionRequest request);

    /** O estado público da chave do usuário na fila, ou vazio se não há (404). Nunca passa pelo disjuntor do {@link #admit}. */
    Optional<AdmissionStatus> result(long userId, String idempotencyKey);
}
