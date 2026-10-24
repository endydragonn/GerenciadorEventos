package com.gerenciador.eventos.identity.api;

/**
 * Interface pública da carteira do usuário (módulo {@code identity}: {@code mywallet} é uma extensão 1:1 de {@code users}, criada
 * por trigger; ficou em {@code public} quando o serviço reservation saiu, issue #33).
 */
public interface MyWalletApi {

    MyWallet getByUserId(Long userId);

    MyWallet ensureExists(Long userId);

    /** A carteira do usuário existe? Não lança se não existir (diferente de {@link #getByUserId}). */
    boolean exists(Long userId);
}
