package com.gerenciador.eventos.identity.api;

/** Autorização do usuário autenticado (papel "host"). */
public interface HostAuthorizationApi {

    /** @throws IllegalArgumentException se não há usuário autenticado ou ele não é host. */
    void ensureUserIsHost();
}
