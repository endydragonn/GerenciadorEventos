package com.gerenciador.eventos.identity;

/**
 * E-mail/senha ou código MFA que não conferem (ou usuário inexistente na verificação MFA): vira HTTP 401 com o marcador de
 * credenciais inválidas ({@link InternalApiExceptionHandler}). O cliente a traduz em {@code null}/{@code false}.
 */
public class CredenciaisInvalidasException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CredenciaisInvalidasException(String message) {
        super(message);
    }
}
