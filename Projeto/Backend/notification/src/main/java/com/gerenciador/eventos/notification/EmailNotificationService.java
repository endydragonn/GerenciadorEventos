package com.gerenciador.eventos.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Envio de e-mail (SMTP) do serviço {@code notification}; o texto das mensagens segue em {@link EmailTemplates}.
 * Falha de envio lança {@link MailException}: quem decide o que fazer (reentregar ou mandar ao dead-letter) é o
 * {@link NotificationConsumer}. Antes, no monólito, a falha do código de cadastro era engolida e a do MFA virava
 * {@code RuntimeException} na requisição; agora o envio é assíncrono e a falha nunca chega ao usuário que cadastrou.
 */
@Service
public class EmailNotificationService {

    private final JavaMailSender mailSender;
    private final String fromEmail;

    public EmailNotificationService(JavaMailSender mailSender, @Value("${spring.mail.username}") String fromEmail) {
        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
    }

    /** Código de verificação do cadastro em duas fases. */
    public void sendVerificationCode(String toEmail, String userName, String verificationCode, int expirationMinutes) {
        send(toEmail, EmailTemplates.getVerificationCodeSubject(),
                EmailTemplates.getVerificationCodeTemplate(userName, verificationCode, expirationMinutes));
    }

    /** Código MFA do reset de senha. */
    public void sendPasswordResetMfa(String toEmail, String userName, String mfaCode, int expirationMinutes) {
        send(toEmail, EmailTemplates.getPasswordResetMfaSubject(),
                EmailTemplates.getPasswordResetMfaTemplate(userName, mfaCode, expirationMinutes));
    }

    private void send(String toEmail, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(text);
        mailSender.send(message);
    }
}
