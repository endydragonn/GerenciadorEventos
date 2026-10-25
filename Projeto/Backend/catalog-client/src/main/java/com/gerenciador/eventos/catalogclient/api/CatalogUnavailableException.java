package com.gerenciador.eventos.catalogclient.api;

/**
 * O serviço catalog não respondeu de forma utilizável (issue #35): falha de rede, timeout, resposta 5xx ou outro status
 * inesperado. O gateway a mapeia para 503 genérico; nunca vira "não encontrado" nem {@code exists == false}.
 */
public class CatalogUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
