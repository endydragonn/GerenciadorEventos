package com.gerenciador.eventos.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gerenciador.eventos.shared.events.EventStore;
import com.gerenciador.eventos.shared.events.NovoEvento;

/**
 * Eventos de ciclo de vida do usuário (issue #36): stream {@value #STREAM_TYPE} (= nome do tópico), UM stream por usuário
 * ({@code streamId} = {@code partitionKey} = {@code user_id}, versões contíguas). Appendados na MESMA transação da escrita em
 * {@code identity.users} ({@link #userRegistered}, {@link #userUpdated}, {@link #userDeactivated}, {@link #userPasswordChanged}).
 *
 * <p><b>Payload só com ids</b> ({@code userId}, {@code schemaVersion} 1): sem e-mail, nome ou qualquer dado pessoal (a única exceção
 * do serviço é o {@code identity.notification}, ver {@code NotificationEvents}). O CRUD continua sendo a fonte de verdade
 * ({@code users} não é projeção do log): o stream é o aviso para quem quiser reagir, sem consumer nesta issue.</p>
 *
 * <p>A versão é {@code MAX(version) + 1} lida na transação do chamador. Duas escritas concorrentes no mesmo usuário podem calcular a
 * mesma versão: a segunda perde no {@code ON CONFLICT} do {@link EventStore#append} (espera o commit da primeira) e relê.</p>
 */
@Component
public class IdentityEvents {

    public static final String STREAM_TYPE = "identity.user";
    public static final String USER_REGISTERED = "UserRegistered";
    public static final String USER_UPDATED = "UserUpdated";
    public static final String USER_DEACTIVATED = "UserDeactivated";
    public static final String USER_PASSWORD_CHANGED = "UserPasswordChanged";

    private static final int TENTATIVAS = 5;

    private final EventStore eventStore;
    private final ObjectMapper json;

    public IdentityEvents(EventStore eventStore, ObjectMapper json) {
        this.eventStore = eventStore;
        this.json = json;
    }

    /** O tópico existe desde o boot (sem consumer nesta issue, mas o relay publica nele). */
    @Bean
    NewTopic identityUserTopic() {
        return TopicBuilder.name(STREAM_TYPE).partitions(1).replicas(1).build();
    }

    public void userRegistered(Connection conn, long userId) throws SQLException {
        append(conn, USER_REGISTERED, userId);
    }

    public void userUpdated(Connection conn, long userId) throws SQLException {
        append(conn, USER_UPDATED, userId);
    }

    public void userDeactivated(Connection conn, long userId) throws SQLException {
        append(conn, USER_DEACTIVATED, userId);
    }

    public void userPasswordChanged(Connection conn, long userId) throws SQLException {
        append(conn, USER_PASSWORD_CHANGED, userId);
    }

    private void append(Connection conn, String eventType, long userId) throws SQLException {
        String streamId = Long.toString(userId);
        ObjectNode payload = json.createObjectNode().put("userId", userId).put("schemaVersion", 1);
        for (int i = 0; i < TENTATIVAS; i++) {
            int versao = proximaVersao(conn, streamId);
            if (eventStore.append(conn, NovoEvento.of(STREAM_TYPE, streamId, versao, eventType, payload, streamId)).isPresent()) {
                return;
            }
        }
        throw new SQLException("Conflito de versão persistente no stream " + STREAM_TYPE + "/" + streamId);
    }

    private int proximaVersao(Connection conn, String streamId) throws SQLException {
        String sql = "SELECT COALESCE(MAX(version), 0) + 1 FROM " + eventStore.tabela("event_store")
                + " WHERE stream_type = ? AND stream_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, STREAM_TYPE);
            ps.setString(2, streamId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
