package com.gerenciador.eventos.catalogclient.api;

/**
 * O evento não existe (HTTP 404 da API interna do catalog, issue #35). A mensagem começa com "Evento não encontrado", como a
 * {@code RuntimeException} que o catalog lançava dentro do monólito (o gateway ainda a reconhece por essa mensagem).
 *
 * <p>O 404 do catalog leva o cabeçalho {@link #HEADER}: um 404 sem ele (rota inexistente, URL apontando para outro serviço,
 * proxy) NÃO é "o evento não existe", e o cliente o trata como catalog indisponível.</p>
 */
public class EventoNaoEncontradoException extends RuntimeException {

    /** Cabeçalho que marca o 404 "evento não existe" da API interna do catalog. */
    public static final String HEADER = "X-Catalog-Nao-Encontrado";

    private static final long serialVersionUID = 1L;

    public EventoNaoEncontradoException(Long id) {
        this("Evento não encontrado: id=" + id);
    }

    public EventoNaoEncontradoException(String message) {
        super(message);
    }
}
