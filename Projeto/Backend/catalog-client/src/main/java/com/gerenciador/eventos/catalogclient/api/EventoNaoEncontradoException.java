package com.gerenciador.eventos.catalogclient.api;

/**
 * O evento não existe (HTTP 404 da API interna do catalog, issue #35). A mensagem começa com "Evento não encontrado", como a
 * {@code RuntimeException} que o catalog lançava dentro do monólito (o gateway ainda a reconhece por essa mensagem).
 */
public class EventoNaoEncontradoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EventoNaoEncontradoException(Long id) {
        this("Evento não encontrado: id=" + id);
    }

    public EventoNaoEncontradoException(String message) {
        super(message);
    }
}
