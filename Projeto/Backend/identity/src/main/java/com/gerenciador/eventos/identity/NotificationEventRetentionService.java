package com.gerenciador.eventos.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventStore;
import com.gerenciador.eventos.shared.events.NotificationEvents;

/**
 * Retenção do dado pessoal do stream {@code identity.notification} (issue #32; decisão do usuário, 2026-10-21; no serviço identity, schema {@code identity}, desde a #36): o
 * {@code email}/{@code userName} dos eventos de pedido de e-mail só vivem no {@code event_store} enquanto o código vale,
 * mais {@link NotificationEvents#CARENCIA} (30 s). A validade vem do próprio evento ({@code payload.expiresAt}, gravada
 * a partir das constantes de validade do cadastro e do MFA), então não há prazo duplicado aqui.
 *
 * <p>Só apaga linhas que já saíram do {@code event_outbox} (um evento ainda não publicado pelo relay não some, senão o
 * relay perderia a mensagem). Evento sem {@code expiresAt} (anterior a esta regra) conta como vencido em
 * {@code occurred_at}. Esse stream tem 1 versão por stream e nunca passa por replay nem projeção, então apagar não
 * quebra nada; a "imutabilidade" do {@code event_store} é convenção (a tabela não tem trigger nem REVOKE). Apaga em lotes
 * de {@value #LOTE}, um por instrução (autocommit), como o {@code IdempotencyKeyRetentionService}. O tópico e o
 * {@code .DLT} têm a retenção equivalente no broker ({@link NotificationEvents#RETENCAO_TOPICO}). {@link #purgeExpired()}
 * é {@code public} e chamável direto (os testes o invocam; o {@code run-tests.sh} estica o timer).</p>
 */
@Service
public class NotificationEventRetentionService {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventRetentionService.class);

    /** Tamanho do lote do DELETE. */
    static final int LOTE = 1000;

    /**
     * Forma do {@code Instant.toString()}. Payload sem {@code expiresAt} ou malformado cai no {@code occurred_at}: o cast
     * {@code ::timestamptz} de um texto qualquer lançaria erro e travaria o job (e a retenção) para sempre.
     */
    private static final String ISO_INSTANT = "^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d+)?Z$";

    private final DatabaseConnection db;
    private final String delete;

    public NotificationEventRetentionService(DatabaseConnection db, EventStore eventStore) {
        this.db = db;
        String store = eventStore.tabela("event_store");
        String outbox = eventStore.tabela("event_outbox");
        this.delete = "DELETE FROM " + store + " WHERE global_position IN ("
                + "SELECT e.global_position FROM " + store + " e WHERE e.stream_type = ? "
                + "AND CASE WHEN e.payload->>'" + NotificationEvents.EXPIRES_AT + "' ~ '" + ISO_INSTANT + "' "
                + "THEN (e.payload->>'" + NotificationEvents.EXPIRES_AT + "')::timestamptz ELSE e.occurred_at END "
                + "< clock_timestamp() - make_interval(secs => ?) "
                + "AND NOT EXISTS (SELECT 1 FROM " + outbox + " o WHERE o.global_position = e.global_position) "
                + "ORDER BY e.global_position LIMIT ?)";
    }

    /** Apaga os eventos de notificação vencidos há mais que a carência e já publicados. Devolve quantos apagou. */
    @Scheduled(fixedDelayString = "${notification.events.retention.scan.ms:30000}",
               initialDelayString = "${notification.events.retention.scan.ms:30000}")
    public int purgeExpired() {
        int total = 0;
        int apagadas;
        try {
            do {
                apagadas = apagarLote();
                total += apagadas;
            } while (apagadas == LOTE);
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao apagar notificações vencidas do event_store", e);
        }
        if (total > 0) {
            log.info("[NotificationRetention] {} evento(s) de notificação vencido(s) apagado(s) do event_store", total);
        }
        return total;
    }

    private int apagarLote() throws SQLException {
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(delete)) {
            ps.setString(1, NotificationEvents.STREAM_TYPE);
            ps.setDouble(2, NotificationEvents.CARENCIA.toSeconds());
            ps.setInt(3, LOTE);
            return ps.executeUpdate();
        }
    }
}
