package com.gerenciador.eventos.gateway.security;

/** Autorização do usuário autenticado (papel "host"), lida dos claims do JWT do identity (issue #36): nunca consulta o banco. */
public interface HostAuthorizationApi {

    /** @throws IllegalArgumentException se não há usuário autenticado ou ele não é host. */
    void ensureUserIsHost();

    /** Id do usuário do token (claim {@code uid}; issue #35: dono do evento). @throws IllegalArgumentException se não há usuário autenticado. */
    Long currentUserId();
}
