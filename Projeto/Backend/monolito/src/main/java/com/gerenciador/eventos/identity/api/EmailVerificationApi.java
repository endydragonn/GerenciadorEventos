package com.gerenciador.eventos.identity.api;

import java.sql.SQLException;

/** Interface pública do cadastro em duas fases (pending_verifications → users). */
public interface EmailVerificationApi {

    void requestEmailVerification(String email, String fone, String userName, String password, String birthDate)
            throws SQLException, IllegalArgumentException;

    User verifyEmailAndCreateUser(String email, String verificationCode)
            throws SQLException, IllegalArgumentException;

    void resendVerificationCode(String email) throws SQLException, IllegalArgumentException;
}
