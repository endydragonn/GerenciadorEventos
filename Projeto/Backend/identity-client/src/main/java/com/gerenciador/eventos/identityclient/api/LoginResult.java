package com.gerenciador.eventos.identityclient.api;

/** Usuário autenticado ({@code login}) ou recém-criado ({@code verify-email}) com o token JWT emitido pelo identity. */
public record LoginResult(User user, String token) {
}
