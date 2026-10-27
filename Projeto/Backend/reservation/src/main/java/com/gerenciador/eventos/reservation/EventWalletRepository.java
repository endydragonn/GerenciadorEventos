package com.gerenciador.eventos.reservation;

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

import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.reservationclient.api.EventWallet;

/**
 * Repository JDBC da tabela de reserva/inscrição reservation.walletevent (user_id, event_id).
 *
 * <p>A linha carrega o {@code status} da máquina de estados
 * ({@code pending -> confirmed / expired / cancelled}). As operações que precisam ser
 * atômicas com o agregado {@code eventcap} (reservar, expirar, cancelar) recebem a
 * {@link Connection} da transação do serviço chamador.</p>
 */
@Repository
public class EventWalletRepository implements ReservationWriter {

    @Autowired
    private DatabaseConnection databaseConnection;

    /**
     * Cria (ou revive, se estava expired/cancelled) a reserva como {@code pending}.
     * Deve ser chamada DEPOIS de um {@code tryReserve} bem-sucedido, na mesma transação.
     * Se já existir uma reserva {@code pending}/{@code confirmed}, o UPSERT não altera nada
     * e o método devolve {@code 0} (o chamador deve então liberar a vaga / dar rollback).
     *
     * <p>A guarda é o próprio UPSERT, e a versão do evento sai dela ({@code RETURNING version}, sem SELECT
     * separado): a 1ª reserva do par é a versão 1; reviver uma cancelada/expirada continua o mesmo stream
     * ({@code version + 1}). A linha fica travada até o commit do chamador.</p>
     * @return a {@code version} do stream após a transição, ou {@code 0} se a guarda não afetou linha.
     */
    @Override
    public int reservePending(Connection conn, Long userId, Long eventId, LocalDateTime expiresAt)
            throws SQLException {
        String sql = "INSERT INTO reservation.walletevent (user_id, event_id, status, expires_at, version) "
                + "VALUES (?, ?, 'pending', ?, 1) "
                + "ON CONFLICT (user_id, event_id) DO UPDATE "
                + "  SET status = 'pending', expires_at = EXCLUDED.expires_at, "
                + "      version = walletevent.version + 1, updated_at = CURRENT_TIMESTAMP "
                + "  WHERE walletevent.status IN ('expired', 'cancelled') "
                + "RETURNING version";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.setLong(2, eventId);
            stmt.setTimestamp(3, Timestamp.valueOf(expiresAt));
            return versaoDaGuarda(stmt);
        }
    }

    /**
     * {@code pending -> confirmed}, na transação do chamador (que appenda o evento na mesma transação).
     * @return a {@code version} após a transição, ou {@code 0} se não havia reserva pending.
     */
    public int confirm(Connection conn, Long userId, Long eventId) throws SQLException {
        String sql = "UPDATE reservation.walletevent "
                + "SET status = 'confirmed', expires_at = NULL, version = version + 1, updated_at = CURRENT_TIMESTAMP "
                + "WHERE user_id = ? AND event_id = ? AND status = 'pending' "
                + "RETURNING version";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.setLong(2, eventId);
            return versaoDaGuarda(stmt);
        }
    }

    /**
     * Marca a reserva como {@code expired} SOMENTE se ela ainda estiver {@code pending} e
     * vencida — evita expirar uma reserva que o usuário confirmou entre a varredura do job
     * e este UPDATE. @return a {@code version} após a transição, ou {@code 0} se não expirou.
     */
    public int expireIfStale(Connection conn, Long userId, Long eventId) throws SQLException {
        String sql = "UPDATE reservation.walletevent "
                + "SET status = 'expired', expires_at = NULL, version = version + 1, updated_at = CURRENT_TIMESTAMP "
                + "WHERE user_id = ? AND event_id = ? AND status = 'pending' "
                + "  AND expires_at < CURRENT_TIMESTAMP "
                + "RETURNING version";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.setLong(2, eventId);
            return versaoDaGuarda(stmt);
        }
    }

    /**
     * Cancela a reserva SOMENTE se ela ainda estiver ativa ({@code pending}/{@code confirmed}),
     * numa única sentença condicional — mesmo padrão de {@link #expireIfStale}. Evita a
     * devolução dupla da vaga quando duas remoções concorrem (dois cliques, retry de rede, ou
     * corrida com o {@link com.gerenciador.eventos.reservation.ReservationExpiryService}).
     * Usa a transação do chamador.
     * @return a {@code version} após a transição ({@code > 0}: cancelou de fato, a vaga deve ser devolvida);
     *         {@code 0} se já estava cancelled/expired ou não existe (nada a devolver).
     */
    public int cancelIfActive(Connection conn, Long userId, Long eventId) throws SQLException {
        String sql = "UPDATE reservation.walletevent "
                + "SET status = 'cancelled', expires_at = NULL, version = version + 1, updated_at = CURRENT_TIMESTAMP "
                + "WHERE user_id = ? AND event_id = ? AND status IN ('pending', 'confirmed') "
                + "RETURNING version";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.setLong(2, eventId);
            return versaoDaGuarda(stmt);
        }
    }

    /** Executa a guarda com {@code RETURNING version}: a versão se afetou 1 linha, {@code 0} se não afetou. */
    private static int versaoDaGuarda(PreparedStatement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Status atual da reserva, ou {@code null} se não existe. */
    public String currentStatus(Long userId, Long eventId) {
        String sql = "SELECT status FROM reservation.walletevent WHERE user_id = ? AND event_id = ?";
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

    /**
     * Um lote de reservas pending já vencidas, mais antigas primeiro, TRAVADAS ({@code FOR UPDATE SKIP LOCKED}) na
     * transação do chamador: linha que outra transação (outra instância do job, um confirm) já segura é pulada, e a
     * travada aqui só solta no commit/rollback do chamador, que expira/libera/appenda na MESMA transação. Em
     * autocommit o lock acabaria junto com a consulta, por isso exige a {@link Connection} da transação.
     * É o que o {@link ReservationExpiryService} usa.
     */
    public List<EventWallet> findExpiredPendingForUpdate(Connection conn, int limit) throws SQLException {
        String sql = "SELECT user_id, event_id, status, expires_at, created_at, updated_at "
                + "FROM reservation.walletevent "
                + "WHERE status = 'pending' AND expires_at < CURRENT_TIMESTAMP "
                + "ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED";
        List<EventWallet> out = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        }
        return out;
    }

    /**
     * Reservas pending já vencidas, todas e sem lock — só leitura (testes). O job de expiração NÃO usa mais este
     * método: usa {@link #findExpiredPendingForUpdate} (em lotes, travando as linhas).
     */
    public List<EventWallet> findExpiredPending() {
        String sql = "SELECT * FROM reservation.walletevent WHERE status = 'pending' AND expires_at < CURRENT_TIMESTAMP";
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
        String sql = "SELECT * FROM reservation.walletevent WHERE user_id = ? ORDER BY created_at DESC";
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
