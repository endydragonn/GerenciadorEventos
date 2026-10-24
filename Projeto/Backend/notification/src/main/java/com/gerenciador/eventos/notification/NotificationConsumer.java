package com.gerenciador.eventos.notification;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventEnvelope;
import com.gerenciador.eventos.shared.events.InvalidEventMessageException;
import com.gerenciador.eventos.shared.events.NotificationEvents;

import jakarta.mail.SendFailedException;

/**
 * Consumer idempotente do tópico {@code identity.notification} (issue #32). A cada mensagem, numa transação de banco
 * só: (1) registra o {@code eventId} em {@code notification.processed_event} ({@code INSERT ... ON CONFLICT DO
 * NOTHING}; rowcount 0 = reentrega, descarta sem enviar), (2) envia o e-mail, (3) comita. O offset só avança depois
 * disso (ack manual). Se o envio falha, a transação é desfeita (o evento NÃO vira checkpoint) e a mensagem volta.
 * Entrega at-least-once: queda entre o envio e o commit manda o e-mail de novo, o preço de não perder nenhum.
 *
 * <p>Por que não o {@code IdempotentConsumer} da {@code shared.events}: ele confere o {@code eventId} contra o
 * {@code public.event_store} e preenche buraco relendo o log, ou seja, depende de tabelas do MONÓLITO. O serviço
 * extraído não lê o banco de ninguém: cada notificação é um stream de uma versão só, então o checkpoint por evento
 * basta. O que é comum (envelope, política de erro/DLT de {@code EventsKafkaConfig}) vem da {@code shared}.</p>
 *
 * <p>Política de erro (a da {@code EventsKafkaConfig}): payload inválido ou rejeição PERMANENTE do SMTP (endereço
 * inválido, ver {@link #rejeicaoPermanente}) vai direto ao {@code .DLT}, sem travar os e-mails seguintes; qualquer
 * outra falha (SMTP fora, banco fora) é reentregue com backoff, sem limite e sem DLT. O tópico tem 1 partição e este
 * listener 1 thread, então só há 1 transação aberta por vez (o pool Hikari do serviço é de 3).</p>
 *
 * <p>{@code idIsGroup = false} é obrigatório: o group vem de {@code spring.kafka.consumer.group-id}.</p>
 */
@Component
public class NotificationConsumer {

    public static final String LISTENER_ID = "notification-email";

    /** Recusas do SMTP que são problema do REMETENTE (quota de envio, limite de taxa), não do endereço: voltam sozinhas. "over quota" (552 5.2.2) é a caixa do DESTINATÁRIO cheia e continua permanente. */
    private static final Pattern CAUSA_DO_REMETENTE =
            Pattern.compile("(?i)sending (?:quota|limit)|rate.?limit|too many (?:messages|connections)|\\b5\\.4\\.5\\b");
    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final DatabaseConnection db;
    private final ObjectMapper json;
    private final EmailNotificationService email;

    public NotificationConsumer(DatabaseConnection db, ObjectMapper json, EmailNotificationService email) {
        this.db = db;
        this.json = json;
        this.email = email;
    }

    /** Garante o tópico (1 partição, retenção curta) também quando o monólito ainda não publicou nada. */
    @Bean
    NewTopic notificationTopic() {
        return NotificationEvents.topic();
    }

    /** O .DLT guarda a mensagem original (com o e-mail): mesma retenção curta do tópico, declarada aqui para não nascer com 7 dias. */
    @Bean
    NewTopic notificationDeadLetterTopic() {
        return NotificationEvents.deadLetterTopic();
    }

    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = NotificationEvents.STREAM_TYPE)
    void ouvir(String value, Acknowledgment ack) throws SQLException {
        EventEnvelope msg = parse(value);
        processar(msg);
        ack.acknowledge();
    }

    /** @return {@code true} se enviou o e-mail, {@code false} se foi reentrega (já enviado) ou tipo desconhecido. */
    boolean processar(EventEnvelope msg) throws SQLException {
        if (!tipoConhecido(msg.eventType())) {
            log.warn("[Notification] tipo de evento desconhecido, ignorado: {} (eventId={})", msg.eventType(), msg.eventId());
            return false;
        }
        if (vencido(msg)) {
            log.warn("[Notification] código vencido, e-mail descartado sem enviar (eventId={})", msg.eventId());
            return false;
        }
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!registrar(conn, msg)) {
                    conn.rollback();
                    log.debug("[Notification] reentrega descartada (eventId={})", msg.eventId());
                    return false;
                }
                enviar(msg);
                conn.commit();
                return true;
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private static boolean tipoConhecido(String tipo) {
        return NotificationEvents.VERIFICATION_CODE_REQUESTED.equals(tipo)
                || NotificationEvents.PASSWORD_RESET_MFA_REQUESTED.equals(tipo);
    }

    /** CAS do checkpoint: rowcount 1 = esta transação é quem envia o e-mail deste evento. */
    private boolean registrar(Connection conn, EventEnvelope msg) throws SQLException {
        String sql = "INSERT INTO notification.processed_event (event_id, event_type) VALUES (?, ?) "
                + "ON CONFLICT DO NOTHING";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, msg.eventId());
            ps.setString(2, msg.eventType());
            return ps.executeUpdate() == 1;
        }
    }

    private void enviar(EventEnvelope msg) {
        JsonNode p = msg.payload();
        String to = texto(p, NotificationEvents.EMAIL);
        String nome = texto(p, NotificationEvents.USER_NAME);
        String codigo = texto(p, NotificationEvents.CODE);
        int minutos = p.path(NotificationEvents.EXPIRATION_MINUTES).asInt(0);
        if (minutos < 1) {
            throw new InvalidEventMessageException("Payload sem " + NotificationEvents.EXPIRATION_MINUTES, null);
        }
        try {
            if (NotificationEvents.VERIFICATION_CODE_REQUESTED.equals(msg.eventType())) {
                email.sendVerificationCode(to, nome, codigo, minutos);
            } else {
                email.sendPasswordResetMfa(to, nome, codigo, minutos);
            }
        } catch (MailException ex) {
            if (rejeicaoPermanente(ex)) {
                throw new InvalidEventMessageException("SMTP rejeitou o destinatário (eventId=" + msg.eventId() + ")", ex);
            }
            throw ex;
        }
    }

    /**
     * O código do e-mail já venceu ({@code payload.expiresAt}): mandar um código vencido é inútil, e o dado pessoal só deve
     * viver enquanto o código vale (decisão do usuário, ver {@link NotificationEvents}). Sem {@code expiresAt} válido o
     * evento é malformado e vai ao DLT.
     */
    private static boolean vencido(EventEnvelope msg) {
        String expiresAt = texto(msg.payload(), NotificationEvents.EXPIRES_AT);
        try {
            return !Instant.now().isBefore(Instant.parse(expiresAt));
        } catch (DateTimeException ex) {
            throw new InvalidEventMessageException("Payload com " + NotificationEvents.EXPIRES_AT + " inválido", ex);
        }
    }

    private static String texto(JsonNode payload, String campo) {
        String v = payload == null ? null : payload.path(campo).asText(null);
        if (v == null || v.isBlank()) {
            throw new InvalidEventMessageException("Payload sem " + campo, null);
        }
        return v;
    }

    /**
     * Reentregar não conserta: endereço malformado ({@link MailParseException}/{@link MailPreparationException}) ou
     * destinatário que o servidor SMTP recusou de vez ({@link SendFailedException#getInvalidAddresses()}, que o
     * JavaMail preenche para 5xx; os 4xx temporários ficam em {@code validUnsent} e continuam reentregues). Sem isto
     * um e-mail digitado errado travaria, para sempre, todos os e-mails seguintes do tópico.
     *
     * <p>Exceção: um 5xx no {@code RCPT TO} causado pelo REMETENTE (quota diária do Gmail, {@code 550 5.4.5}, limite de
     * taxa) também aparece em {@code invalidAddresses}, mas passa quando a quota volta: continua reentregue
     * ({@link #CAUSA_DO_REMETENTE}, casado no texto das exceções encadeadas).</p>
     */
    static boolean rejeicaoPermanente(MailException ex) {
        if (ex instanceof MailParseException || ex instanceof MailPreparationException) {
            return true;
        }
        List<Throwable> raizes = new ArrayList<>();
        raizes.add(ex);
        if (ex instanceof MailSendException s && s.getMessageExceptions() != null) {
            raizes.addAll(Arrays.asList(s.getMessageExceptions()));
        }
        boolean enderecoRecusado = false;
        for (Throwable raiz : raizes) {
            for (Throwable t = raiz; t != null; t = t.getCause()) {
                if (t.getMessage() != null && CAUSA_DO_REMETENTE.matcher(t.getMessage()).find()) {
                    return false;
                }
                if (t instanceof SendFailedException f && f.getInvalidAddresses() != null
                        && f.getInvalidAddresses().length > 0) {
                    enderecoRecusado = true;
                }
            }
        }
        return enderecoRecusado;
    }

    private EventEnvelope parse(String value) {
        if (value == null) {
            throw new InvalidEventMessageException("Mensagem sem corpo", null);
        }
        try {
            EventEnvelope e = json.readValue(value, EventEnvelope.class);
            if (e == null || e.eventId() == null || e.eventType() == null) {
                throw new InvalidEventMessageException("Envelope sem campos obrigatórios", null);
            }
            return e;
        } catch (JsonProcessingException ex) {
            throw new InvalidEventMessageException("Mensagem não é um envelope de evento válido", ex);
        }
    }
}
