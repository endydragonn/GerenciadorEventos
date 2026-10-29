package com.gerenciador.eventos.stockclient.api;

/**
 * HTTP 409 de {@code POST /internal/holds}: o hold já foi liberado (lápide). Um "reservar" atrasado, depois da compensação, não debita
 * nada; o chamador trata como falha da tentativa.
 */
public class HoldLiberadoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HoldLiberadoException(String message) {
        super(message);
    }
}
