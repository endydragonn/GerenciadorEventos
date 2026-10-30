package com.gerenciador.eventos.reservation;

/**
 * 503 da API interna na saga stock + wallet (#47): a reserva não pôde ser concluída agora e repetir com a mesma {@code Idempotency-Key}
 * é seguro e é o que se espera ("Reserva em andamento, tente novamente", "Reserva não concluída, tente novamente"; também o stock
 * fora na leitura da disponibilidade). O corpo da resposta é a mensagem em texto puro. O {@code ReservationClient} NÃO a distingue: 503 vira
 * {@code RuntimeException} (gateway: 500 genérico; worker do admission: falha transitória, requeue).
 */
public class SagaIndisponivelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SagaIndisponivelException(String message) {
        super(message);
    }

    public SagaIndisponivelException(String message, Throwable cause) {
        super(message, cause);
    }
}
