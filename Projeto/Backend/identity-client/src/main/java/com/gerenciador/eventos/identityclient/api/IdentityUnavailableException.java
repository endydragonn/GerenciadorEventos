package com.gerenciador.eventos.identityclient.api;

/**
 * O serviço identity não respondeu de forma utilizável (issue #36): falha de rede, timeout, resposta 5xx, 404 sem o marcador ou
 * outro status inesperado. O gateway a mapeia para 503 genérico; nunca vira "não encontrado", "credenciais inválidas" nem
 * {@code exists == false}.
 */
public class IdentityUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdentityUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
