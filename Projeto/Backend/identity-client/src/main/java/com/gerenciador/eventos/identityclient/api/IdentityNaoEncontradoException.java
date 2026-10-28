package com.gerenciador.eventos.identityclient.api;

/**
 * Usuário ou carteira inexistente (HTTP 404 da API interna do identity, issue #36). A mensagem é a que a implementação local
 * sempre deu ("Usuário não encontrado com ID: ...", "Carteira não encontrada para user_id=...").
 *
 * <p>O 404 do identity leva o cabeçalho {@link #HEADER}: um 404 sem ele (rota inexistente, URL de outro serviço, proxy) NÃO é
 * "não existe", e o cliente o trata como identity indisponível.</p>
 */
public class IdentityNaoEncontradoException extends RuntimeException {

    /** Cabeçalho que marca o 404 "recurso não existe" da API interna do identity. */
    public static final String HEADER = "X-Identity-Nao-Encontrado";

    private static final long serialVersionUID = 1L;

    public IdentityNaoEncontradoException(String message) {
        super(message);
    }
}
