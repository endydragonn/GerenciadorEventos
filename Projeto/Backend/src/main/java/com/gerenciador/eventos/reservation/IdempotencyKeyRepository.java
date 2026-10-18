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
 * Repository JDBC de {@code public.idempotency_key} (issue #24). A chave é reivindicada na
 * transação da reserva ({@link #claim}); a PK serializa requisições simultâneas com a mesma chave.
 */
@Repository
public class IdempotencyKeyRepository {

    /** Resultado salvo da primeira reserva feita com a chave. */
    public record Salvo(Long userId, Long eventId, LocalDateTime expiresAt) { }

    @Autowired
    private DatabaseConnection databaseConnection;

    /**
     * {@code INSERT ... ON CONFLICT DO NOTHING} na transação do chamador. Se outra transação já
     * inseriu a mesma chave mas ainda não terminou, bloqueia até ela dar commit/rollback.
     * @return {@code true} se a chave era nova (esta requisição é a primeira); {@code false} se já existe.
     */
    public boolean claim(Connection conn, String key, Long userId, Long eventId, LocalDateTime expiresAt)
            throws SQLException {
        String sql = "INSERT INTO idempotency_key (idempotency_key, user_id, event_id, expires_at) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, key);
            stmt.setLong(2, userId);
            stmt.setLong(3, eventId);
            stmt.setTimestamp(4, Timestamp.valueOf(expiresAt));
            return stmt.executeUpdate() == 1;
        }
    }

    /** Lê a chave na transação do chamador (READ COMMITTED: enxerga o commit da vencedora). */
    public Salvo find(Connection conn, String key) throws SQLException {
        String sql = "SELECT user_id, event_id, expires_at FROM idempotency_key WHERE idempotency_key = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next()
                        ? new Salvo(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toLocalDateTime())
                        : null;
            }
        }
    }

    /** Lê a chave numa conexão própria; {@code null} se não existe. */
    public Salvo find(String key) {
        try (Connection conn = databaseConnection.getConnection()) {
            return find(conn, key);
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao consultar Idempotency-Key: " + ex.getMessage(), ex);
        }
    }
}
