package com.gerenciador.eventos.reservation.api;

/** Interface pública da carteira do usuário (módulo {@code reservation}). */
public interface MyWalletApi {

    MyWallet getByUserId(Long userId);

    MyWallet ensureExists(Long userId);
}
