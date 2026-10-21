package com.gerenciador.eventos.notification;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;

/** Quando o SMTP recusar o envio é definitivo (DLT) e quando volta sozinho (reentrega). Sem Spring, sem banco. */
public class RejeicaoPermanenteTest {

    private static MailSendException recusa(String texto) throws Exception {
        Address[] invalidos = {new InternetAddress("alguem@example.com")};
        return new MailSendException("falhou",
                new SendFailedException(texto, null, new Address[0], new Address[0], invalidos));
    }

    @Test
    void usuarioInexistenteEPermanente() throws Exception {
        assertTrue(NotificationConsumer.rejeicaoPermanente(recusa("550 5.1.1 The email account does not exist")));
    }

    @Test
    void enderecoMalformadoEPermanente() {
        assertTrue(NotificationConsumer.rejeicaoPermanente(new MailParseException("endereço inválido")));
    }

    @Test
    void quotaDoGmailNoRcptToNaoEPermanente() throws Exception {
        assertFalse(NotificationConsumer.rejeicaoPermanente(recusa("550 5.4.5 Daily user sending quota exceeded.")));
        assertFalse(NotificationConsumer.rejeicaoPermanente(recusa("550 rate limit exceeded, try later")));
    }

    @Test
    void caixaDoDestinatarioCheiaEPermanente() throws Exception {
        assertTrue(NotificationConsumer.rejeicaoPermanente(recusa("552 5.2.2 The email account is over quota.")));
        assertTrue(NotificationConsumer.rejeicaoPermanente(recusa("552 Mailbox size limit exceeded")));
        assertFalse(NotificationConsumer.rejeicaoPermanente(recusa("550 Daily sending limit reached")));
    }

    @Test
    void quotaEncadeadaNaCausaTambemNaoEPermanente() throws Exception {
        Address[] invalidos = {new InternetAddress("alguem@example.com")};
        SendFailedException interna = new SendFailedException("550 5.4.5 Daily user sending quota exceeded",
                null, new Address[0], new Address[0], invalidos);
        assertFalse(NotificationConsumer.rejeicaoPermanente(new MailSendException("falhou",
                new MessagingException("envoltório", interna))));
    }

    @Test
    void faltaDeConexaoEAutenticacaoSaoTransitorias() {
        assertFalse(NotificationConsumer.rejeicaoPermanente(
                new MailSendException("connect timed out", new MessagingException("connect timed out"))));
        assertFalse(NotificationConsumer.rejeicaoPermanente(new MailAuthenticationException("535 bad credentials")));
    }
}
