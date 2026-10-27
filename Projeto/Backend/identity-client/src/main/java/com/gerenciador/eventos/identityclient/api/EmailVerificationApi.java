package com.gerenciador.eventos.identityclient.api;

import java.sql.SQLException;

/**
 * Cadastro em duas fases (pending_verifications → users). {@code throws SQLException} é herança da implementação local do
 * monólito (o {@code IdentityClient} HTTP nunca a lança); sai junto com ela no cutover do identity (#36, fatia 3).
 */
public interface EmailVerificationApi {

    void requestEmailVerification(String email, String fone, String userName, String password, String birthDate)
            throws SQLException, IllegalArgumentException;

    /** Cria o usuário (código certo) e devolve ele já com o token JWT do login automático. */
    LoginResult verifyEmailAndCreateUser(String email, String verificationCode)
            throws SQLException, IllegalArgumentException;

    void resendVerificationCode(String email) throws SQLException, IllegalArgumentException;
}
