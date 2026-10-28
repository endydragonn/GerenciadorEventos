package com.gerenciador.eventos.gateway;

/**
 * O evento está em modo fila (issue #43) e a tentativa de reserva foi enfileirada pelo admission: nenhuma reserva foi feita ainda. Não é
 * erro: o {@link BffEventWalletController} a transforma em {@code 202 Accepted} com o estado (e a chave de idempotência, no header).
 * Sem stack trace (é fluxo normal sob carga).
 */
public class ReservaEnfileiradaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient EstadoDaReserva estado;

    ReservaEnfileiradaException(EstadoDaReserva estado) {
        super("reserva enfileirada", null, false, false);
        this.estado = estado;
    }

    public EstadoDaReserva estado() {
        return estado;
    }
}
