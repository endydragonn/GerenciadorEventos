package com.gerenciador.eventos.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import com.gerenciador.eventos.notification.api.EmailNotificationApi;

/**
 * Envio de e-mail (SMTP) do módulo {@code notification}. Antes vivia dentro de
 * {@code EmailVerificationService}; o texto das mensagens segue em {@link EmailTemplates}.
 */
@Service
public class EmailNotificationService implements EmailNotificationApi {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    @Autowired
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:enricospanier@gmail.com}")
    private String fromEmail;

    @Override
    public void sendVerificationCode(String toEmail, String userName, String verificationCode, int expirationMinutes) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject(EmailTemplates.getVerificationCodeSubject());
            message.setText(EmailTemplates.getVerificationCodeTemplate(userName, verificationCode, expirationMinutes));

            mailSender.send(message);
        } catch (MailException e) {
            log.error("Erro ao enviar email: {}", e.getMessage());
            // Log erro mas não bloqueia o fluxo
        }
    }

    @Override
    public void sendPasswordResetMfa(String toEmail, String userName, String mfaCode) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject(EmailTemplates.getPasswordResetMfaSubject());
            message.setText(EmailTemplates.getPasswordResetMfaTemplate(userName, mfaCode));

            mailSender.send(message);
            log.info("Email de MFA enviado com sucesso para: {}", toEmail);
        } catch (MailException e) {
            log.error("Erro ao enviar email de MFA: {}", e.getMessage());
            throw new RuntimeException("Erro ao enviar email de MFA: " + e.getMessage());
        }
    }
}
