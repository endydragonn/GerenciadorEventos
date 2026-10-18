package com.gerenciador.eventos.notification.api;

/** Interface pública do módulo {@code notification}: envio de e-mails transacionais. */
public interface EmailNotificationApi {

    /** Envia o código de cadastro. Falha de envio é só logada (não bloqueia o cadastro). */
    void sendVerificationCode(String toEmail, String userName, String verificationCode, int expirationMinutes);

    /** Envia o código MFA de reset de senha. Falha de envio lança {@link RuntimeException}. */
    void sendPasswordResetMfa(String toEmail, String userName, String mfaCode);
}
