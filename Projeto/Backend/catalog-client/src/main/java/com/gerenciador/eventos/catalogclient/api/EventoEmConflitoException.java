package com.gerenciador.eventos.catalogclient.api;

/**
 * Conflito do catálogo (HTTP 409 da API interna, issue #35): apagar evento com reserva (recusado pelo reservation), alterar
 * evento pré-ES ({@code version = 0}) ou conflito de versão no stream que esgotou as tentativas. É a ÚNICA exceção que o serviço
 * mapeia para 409: outra {@link IllegalStateException} (pool fechado, buraco no stream...) é falha interna, 500. Estende
 * {@link IllegalStateException} para quem já tratava o 409 assim.
 */
public class EventoEmConflitoException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public EventoEmConflitoException(String message) {
        super(message);
    }
}
