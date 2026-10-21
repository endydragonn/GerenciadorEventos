package com.gerenciador.eventos.identity.api;

/** Emissão de JWT (a validação fica no filtro de segurança, interno ao {@code identity}). */
public interface TokenApi {

    String generateToken(String subject);

    String generateToken(String subject, Boolean isHost);
}
