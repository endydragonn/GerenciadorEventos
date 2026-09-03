package com.gerenciador.eventos.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.gerenciador.eventos.config.DatabaseConnection;
import com.gerenciador.eventos.POJO.EventWallet;

/**
 * Repository JDBC da tabela de reserva/inscrição public.walletevent (user_id, event_id).
 *
 * <p>A linha carrega o {@code status} da máquina de estados
 * ({@code pending -> confirmed / expired / cancelled}). As operações que precisam ser
 * atômicas com o agregado {@code eventcap} (reservar, expirar, cancelar) recebem a
 * {@link Connection} da transação do serviço chamador.</p>
 */
@Repository
public class EventWalletRepository {

    @Autowired
    private DatabaseConnection databaseConnection;

    /**
     * Cria (ou revive, se estava expired/cancelled) a reserva como {@code pending}.
     * Deve ser chamada DEPOIS de um {@code tryReserve} bem-sucedido, na mesma transação.
     * Se já existir uma reserva {@code pending}/{@code confirmed}, o UPSERT não altera nada
     * e o método devolve {@code false} (o chamador deve então liberar a vaga / dar rollback).
     */
    public boolean reservePending(Connection conn, Long userId, Long eventId, LocalDateTime expiresAt)
            throws SQLException {
        String sql = "INSERT INTO walletevent (user_id, event_id, status, expires_at) "
                + "VALUES (?, ?, 'pending', ?) "
                + "ON CONFLICT (user_id, event_id) DO UPDATE "
                + "  SET status = 'pending', expires_at = EXCLUDED.expires_at, "
                + "      updated_at = CURRENT_TIMESTAMP "
                + "  WHERE walletevent.status IN ('expired', 'cancelled')";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.setLong(2, eventId);
            stmt.setTimestamp(3, Timestamp.valueOf(expiresAt));
            return stmt.executeUpdate() == 1;
        }
    }

    /** {@code pending -> confirmed}. @return linhas afetadas (0 se não havia reserva pending). */
    public int confirm(Long userId, Long eventId) {
        String sql = "UPDATE walletevent "
                + "SET status = 'confirmed', expires_at = NULL, updated_at = CURRENT_TIMESTAMP "
                + "WHERE user_id = ? AND event_id = ? AND status = 'pending'";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.setLong(2, eventId);
            return stmt.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao confirmar reserva: " + ex.getMessage(), ex);
        }
    }

    /**
     * Marca a reserva como {@code expired} SOMENTE se ela ainda estiver {@code pending} e
     * vencida — evita expirar uma reserva que o usuário confirmou entre a varredura do job
     * e este UPDATE. @return {@code true} se expirou de fato.
     */
    public boolean expireIfStale(Connection conn, Long userId, Long eventId) throws SQLException {
        String sql = "UPDATE walletevent "
                + "SET status = 'expired', expires_at = NULL, updated_at = CURRENT_TIMESTAMP "
                + "WHERE user_id = ? AND event_id = ? AND status = 'pending' "
                + "  AND expires_at < CURRENT_TIMESTAMP";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.setLong(2, eventId);
            return stmt.executeUpdate() == 1;
        }
    }

    /**
     * Cancela a reserva SOMENTE se ela ainda estiver ativa ({@code pending}/{@code confirmed}),
     * numa única sentença condicional — mesmo padrão de {@link #expireIfStale}. Evita a
     * devolução dupla da vaga quando duas remoções concorrem (dois cliques, retry de rede, ou
     * corrida com o {@link com.gerenciador.eventos.Service.ReservationExpiryService}).
     * Usa a transação do chamador.
     * @return linhas afetadas: {@code 1} se cancelou de fato (a vaga deve ser devolvida);
     *         {@code 0} se já estava cancelled/expired ou não existe (nada a devolver).
     */
    public int cancelIfActive(Connection conn, Long userId, Long eventId) throws SQLException {
        String sql = "UPDATE walletevent "
                + "SET status = 'cancelled', expires_at = NULL, updated_at = CURRENT_TIMESTAMP "
                + "WHERE user_id = ? AND event_id = ? AND status IN ('pending', 'confirmed')";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.setLong(2, eventId);
            return stmt.executeUpdate();
        }
    }

    /** Status atual da reserva, ou {@code null} se não existe. */
    public String currentStatus(Long userId, Long eventId) {
        String sql = "SELECT status FROM walletevent WHERE user_id = ? AND event_id = ?";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.setLong(2, eventId);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao consultar status da reserva: " + ex.getMessage(), ex);
        }
    }

    /** Existe reserva ativa (pending ou confirmed) para o par? */
    public boolean existsActive(Long userId, Long eventId) {
        String status = currentStatus(userId, eventId);
        return "pending".equals(status) || "confirmed".equals(status);
    }

    /** Reservas pending já vencidas — insumo do job de expiração. */
    public List<EventWallet> findExpiredPending() {
        String sql = "SELECT * FROM walletevent WHERE status = 'pending' AND expires_at < CURRENT_TIMESTAMP";
        List<EventWallet> out = new ArrayList<>();
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                out.add(map(rs));
            }
            return out;
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao listar reservas expiradas: " + ex.getMessage(), ex);
        }
    }

    /** Listar reservas por usuário (todos os status). */
    public List<EventWallet> findAllByUserId(Long userId) {
        String sql = "SELECT * FROM walletevent WHERE user_id = ? ORDER BY created_at DESC";
        List<EventWallet> out = new ArrayList<>();
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                out.add(map(rs));
            }
            return out;
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao listar vínculos da carteira: " + ex.getMessage(), ex);
        }
    }

    /** Remoção física do vínculo (uso administrativo; cancelamento normal usa cancelIfActive). */
    public void delete(Long userId, Long eventId) {
        String sql = "DELETE FROM walletevent WHERE user_id = ? AND event_id = ?";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.setLong(2, eventId);
            stmt.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao apagar vínculo carteira-evento: " + ex.getMessage(), ex);
        }
    }

    // ===== Helpers =====
    private EventWallet map(ResultSet rs) throws SQLException {
        EventWallet ew = new EventWallet();
        ew.setUserId(rs.getLong("user_id"));
        ew.setEventId(rs.getLong("event_id"));
        ew.setStatus(rs.getString("status"));
        Timestamp exp = rs.getTimestamp("expires_at");
        if (exp != null) ew.setExpiresAt(exp.toLocalDateTime());
        Timestamp c = rs.getTimestamp("created_at");
        if (c != null) ew.setCreatedAt(c.toLocalDateTime());
        Timestamp u = rs.getTimestamp("updated_at");
        if (u != null) ew.setUpdatedAt(u.toLocalDateTime());
        return ew;
    }
}
