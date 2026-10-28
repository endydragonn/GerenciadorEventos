package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Repository JDBC de {@code reservation.idempotency_key} (issue #24). A chave é reivindicada na
 * transação da reserva ({@link #claim}); a PK {@code (user_id, idempotency_key)} serializa requisições
 * simultâneas do mesmo usuário com a mesma chave (a chave tem escopo por usuário, issue #129).
 */
@Repository
public class IdempotencyKeyRepository {

    /** Resultado salvo da primeira reserva feita com a chave. */
    public record Salvo(Long eventId, LocalDateTime expiresAt) { }

    @Autowired
    private DatabaseConnection databaseConnection;

    /**
     * {@code INSERT ... ON CONFLICT DO NOTHING} na transação do chamador. Se outra transação já
     * inseriu a mesma chave mas ainda não terminou, bloqueia até ela dar commit/rollback.
     * @return {@code true} se o par (usuário, chave) era novo (esta requisição é a primeira); {@code false} se já existe.
     */
    public boolean claim(Connection conn, String key, Long userId, Long eventId, LocalDateTime expiresAt)
            throws SQLException {
        String sql = "INSERT INTO reservation.idempotency_key (idempotency_key, user_id, event_id, expires_at) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT (user_id, idempotency_key) DO NOTHING";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, key);
            stmt.setLong(2, userId);
            stmt.setLong(3, eventId);
            stmt.setTimestamp(4, Timestamp.valueOf(expiresAt));
            return stmt.executeUpdate() == 1;
        }
    }

    /** Lê o par (usuário, chave) na transação do chamador (READ COMMITTED: enxerga o commit da vencedora). */
    public Salvo find(Connection conn, String key, Long userId) throws SQLException {
        String sql = "SELECT event_id, expires_at FROM reservation.idempotency_key "
                + "WHERE user_id = ? AND idempotency_key = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.setString(2, key);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next()
                        ? new Salvo(rs.getLong(1), rs.getTimestamp(2).toLocalDateTime())
                        : null;
            }
        }
    }

    /** Lê o par (usuário, chave) numa conexão própria; {@code null} se não existe. */
    public Salvo find(String key, Long userId) {
        try (Connection conn = databaseConnection.getConnection()) {
            return find(conn, key, userId);
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao consultar Idempotency-Key: " + ex.getMessage(), ex);
        }
    }

    /**
     * Apaga até {@code lote} chaves com {@code created_at} mais antigo que {@code retencaoHoras}. Um
     * único {@code DELETE} limitado (CTE + junção por {@code ctid}, o idioma do manual do PostgreSQL para
     * "DELETE com LIMIT"): o chamador repete até devolver menos que {@code lote}, assim nenhuma instrução
     * segura locks em tudo de uma vez. O {@code FOR UPDATE} trava só as linhas do lote. A junção por
     * {@code ctid} é estável porque nada faz {@code UPDATE} nessa tabela (o {@code ctid} não muda entre o
     * {@code SELECT} e o {@code DELETE}).
     * @return quantas linhas foram apagadas nesta chamada.
     */
    public int deleteOlderThan(int retencaoHoras, int lote) {
        String sql = "WITH lote AS (SELECT ctid FROM reservation.idempotency_key "
                + "WHERE created_at < now() - make_interval(hours => ?) LIMIT ? FOR UPDATE) "
                + "DELETE FROM reservation.idempotency_key d USING lote WHERE d.ctid = lote.ctid";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, retencaoHoras);
            stmt.setInt(2, lote);
            return stmt.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao apagar Idempotency-Keys vencidas: " + ex.getMessage(), ex);
        }
    }
}
