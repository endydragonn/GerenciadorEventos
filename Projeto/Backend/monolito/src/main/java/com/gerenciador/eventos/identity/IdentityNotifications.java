package com.gerenciador.eventos.identity;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventStore;
import com.gerenciador.eventos.shared.events.NotificationEvents;
import com.gerenciador.eventos.shared.events.NovoEvento;

/**
 * Lado produtor do envio de e-mail (issue #32): em vez de falar com SMTP, {@code identity} appenda um evento no stream
 * {@code identity.notification} (event_store + event_outbox, pelo {@link EventStore}) NA MESMA transação que grava o
 * código ({@code pending_verifications} / {@code users.password_reset_mfa_code}). O relay publica no Redpanda e o
 * serviço {@code notification} (processo à parte) consome e envia. Com ele fora do ar o cadastro/login seguem normais:
 * o evento espera no outbox/tópico até ele voltar. Contrato do payload em {@link NotificationEvents}.
 *
 * <p>Cada notificação é um stream NOVO de uma versão só ({@code streamId} = UUID, {@code version} 1; a chave de
 * partição é o próprio {@code streamId}): não há conflito otimista a tratar e a ordem entre e-mails não importa.</p>
 */
@Component
public class IdentityNotifications {

    /** Corpo de uma transação JDBC que o chamador só quer ver comitada ou desfeita por inteiro. */
    @FunctionalInterface
    public interface Transacao {
        void executar(Connection conn) throws SQLException;
    }

    private final DatabaseConnection db;
    private final EventStore eventStore;
    private final ObjectMapper json;

    public IdentityNotifications(DatabaseConnection db, EventStore eventStore, ObjectMapper json) {
        this.db = db;
        this.eventStore = eventStore;
        this.json = json;
    }

    /** O tópico existe desde o boot do monólito, mesmo que o {@code notification} ainda não tenha subido. */
    @Bean
    NewTopic identityNotificationTopic() {
        return NotificationEvents.topic();
    }

    /** Roda o corpo numa transação JDBC (commit no fim; rollback e relança se algo falhar). */
    public void emTransacao(Transacao corpo) throws SQLException {
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                corpo.executar(conn);
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** Pede o e-mail com o código de verificação do cadastro (pedido e reenvio). */
    public void verificationCodeRequested(Connection conn, String email, String userName, String code,
            int expirationMinutes) throws SQLException {
        ObjectNode payload = base(email, userName, code, Duration.ofMinutes(expirationMinutes));
        append(conn, NotificationEvents.VERIFICATION_CODE_REQUESTED, payload);
    }

    /** Pede o e-mail com o código MFA do reset de senha. */
    public void passwordResetMfaRequested(Connection conn, String email, String userName, String code)
            throws SQLException {
        append(conn, NotificationEvents.PASSWORD_RESET_MFA_REQUESTED,
                base(email, userName, code, NotificationEvents.MFA_VALIDADE));
    }

    /** Payload comum; {@code expiresAt} = quando o código vence (base da retenção do dado pessoal, ver {@link NotificationEvents}). */
    private ObjectNode base(String email, String userName, String code, Duration validade) {
        return json.createObjectNode().put(NotificationEvents.EMAIL, email)
                .put(NotificationEvents.USER_NAME, userName).put(NotificationEvents.CODE, code)
                .put(NotificationEvents.EXPIRATION_MINUTES, validade.toMinutes())
                .put(NotificationEvents.EXPIRES_AT, Instant.now().plus(validade).toString());
    }

    private void append(Connection conn, String eventType, ObjectNode payload) throws SQLException {
        String streamId = UUID.randomUUID().toString();
        eventStore.append(conn, NovoEvento.of(NotificationEvents.STREAM_TYPE, streamId, 1, eventType, payload, streamId))
                .orElseThrow(() -> new SQLException("Stream de notificação novo já existia: " + streamId));
    }
}
