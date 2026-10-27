package com.gerenciador.eventos.identityclient.api;

/**
 * Contrato público da carteira do usuário (módulo {@code identity}: {@code mywallet} é uma extensão 1:1 de {@code users}, criada
 * por trigger). Carteira inexistente em {@link #getByUserId}: {@link IdentityNaoEncontradoException}.
 */
public interface MyWalletApi {

    MyWallet getByUserId(Long userId);

    MyWallet ensureExists(Long userId);

    /**
     * A carteira do usuário existe? Não lança se não existir (diferente de {@link #getByUserId}). Chama-se {@code walletExists}
     * (e não {@code exists}) porque o {@code IdentityClient} implementa {@link UserApi} e esta interface na mesma classe, e
     * {@code UserApi.exists(Long)} já ocupa a assinatura.
     */
    boolean walletExists(Long userId);
}
