package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Repository JDBC de {@code reservation.idempotency_key} (issues #24 e #47). Desde a #47 a linha é também o LOG DA SAGA stock + wallet:
 * {@code reserving} (a tx1 gravou e o hold foi pedido ao stock) {@code -> reserved} (a tx2 comitou) ou {@code -> compensating
 * -> compensated}. A PK {@code (user_id, idempotency_key)} serializa requisições simultâneas do mesmo usuário com a mesma chave (a chave
 * tem escopo por usuário, issue #129).
 *
 * <p><b>Regra das transições:</b> toda transição é um {@code UPDATE} condicional por (PK, {@code hold_id}, status esperado), nunca só pela
 * PK: o fim atrasado de uma tentativa antiga nunca fecha a saga de uma tentativa nova da mesma chave. Cada método abre a própria
 * conexão em autocommit (um commit por transição), exceto {@link #concluir}, que roda na transação da tx2.</p>
 */
@Repository
public class IdempotencyKeyRepository {

    public static final String RESERVING = "reserving";
    public static final String RESERVED = "reserved";
    public static final String COMPENSATING = "compensating";
    public static final String COMPENSATED = "compensated";

    /** A linha da saga: o evento da primeira tentativa, o hold corrente, o estado e o {@code expiresAt} (nulo até a tx2). */
    public record Linha(Long eventId, UUID holdId, String status, LocalDateTime expiresAt) { }

    private static final String PK = "user_id = ? AND idempotency_key = ?";

    @Autowired
    private DatabaseConnection databaseConnection;

    /**
     * tx1: {@code INSERT ... ON CONFLICT DO NOTHING} com o hold novo em {@code reserving}, comitado na hora.
     * @return {@code true} se este pedido é o dono da saga (inseriu); {@code false} se a chave já existe
     */
    public boolean iniciar(String key, Long userId, Long eventId, UUID holdId) {
        // updated_at fica de fora de propósito: vale o DEFAULT now() da coluna (NOT NULL), que é o que o reaper compara (W13)
        String sql = "INSERT INTO reservation.idempotency_key (idempotency_key, user_id, event_id, hold_id, saga_status, expires_at) "
                + "VALUES (?, ?, ?, ?, 'reserving', NULL) ON CONFLICT (user_id, idempotency_key) DO NOTHING";
        return atualizar(sql, key, userId, eventId, holdId) == 1;
    }

    /**
     * Reabre uma saga {@code compensated} (falha não consome a chave) com o hold novo.
     * @return {@code true} se ESTE pedido reabriu; {@code false} se outro reabriu antes (ou a retenção apagou): quem chama relê a linha
     */
    public boolean reabrir(String key, Long userId, UUID holdLido, UUID holdNovo) {
        // created_at = now(): a retenção (deleteOlderThan) conta a partir da tentativa reaberta, não da primeira (já compensada)
        String sql = "UPDATE reservation.idempotency_key SET saga_status = 'reserving', hold_id = ?, expires_at = NULL, "
                + "resolved_at = NULL, resolved_by = NULL, created_at = now(), updated_at = now() WHERE " + PK + " AND hold_id = ? AND saga_status = 'compensated'";
        return executar(sql, ps -> {
            ps.setObject(1, holdNovo);
            ps.setLong(2, userId);
            ps.setString(3, key);
            ps.setObject(4, holdLido);
        }) == 1;
    }

    /** A linha da saga, ou {@code null} se não existe. */
    public Linha ler(String key, Long userId) {
        String sql = "SELECT event_id, hold_id, saga_status, expires_at FROM reservation.idempotency_key WHERE " + PK;
        try (Connection conn = databaseConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Timestamp exp = rs.getTimestamp(4);
                return new Linha(rs.getLong(1), (UUID) rs.getObject(2), rs.getString(3), exp == null ? null : exp.toLocalDateTime());
            }
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao consultar Idempotency-Key: " + ex.getMessage(), ex);
        }
    }

    /** Só a linha {@code reserved} (o resultado salvo): {@code reserving}/{@code compensating} e {@code compensated} não são resultado de nada. */
    public Linha lerReservada(String key, Long userId) {
        Linha l = ler(key, userId);
        return l != null && RESERVED.equals(l.status()) ? l : null;
    }

    /**
     * tx2: o CAS da saga {@code reserving -> reserved} com o {@code expiresAt}, na transação do chamador.
     * @return 1 se concluiu; 0 se o reaper assumiu a saga ou a linha já não é deste {@code hold_id}
     */
    public int concluir(Connection conn, String key, Long userId, UUID holdId, LocalDateTime expiresAt) throws SQLException {
        String sql = "UPDATE reservation.idempotency_key SET saga_status = 'reserved', expires_at = ?, updated_at = now() "
                + "WHERE " + PK + " AND hold_id = ? AND saga_status = 'reserving'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(expiresAt));
            ps.setLong(2, userId);
            ps.setString(3, key);
            ps.setObject(4, holdId);
            return ps.executeUpdate();
        }
    }

    /** O stock recusou (400): apaga a linha {@code reserving} deste hold. Rejeição não consome a chave e não é compensação. */
    public boolean apagarReserving(String key, Long userId, UUID holdId) {
        return transitar("DELETE FROM reservation.idempotency_key WHERE " + PK + " AND hold_id = ? AND saga_status = 'reserving'",
                key, userId, holdId);
    }

    /** {@code reserving -> compensating}, comitado antes de liberar o hold. @return {@code false} se a linha é de outro dono ou do reaper */
    public boolean paraCompensando(String key, Long userId, UUID holdId) {
        return transitar("UPDATE reservation.idempotency_key SET saga_status = 'compensating', updated_at = now() WHERE " + PK
                + " AND hold_id = ? AND saga_status = 'reserving'", key, userId, holdId);
    }

    /** A saga que o reaper trata: o que ele lê numa varredura. */
    public record Orfa(Long userId, String chave, Long eventId, UUID holdId, String status, long idadeMs) { }

    /**
     * Varredura do reaper, NA transação do chamador: as {@code reserving} E as {@code compensating} paradas há mais de {@code orfaMs}
     * ({@code updated_at}; a compensação inline em curso não é tomada),
     * as mais antigas primeiro, até {@code limite}, com {@code FOR UPDATE SKIP LOCKED} (N reapers/réplicas não pegam a mesma linha). Nunca
     * devolve {@code reserved}: o reaper não libera hold de saga concluída.
     */
    public List<Orfa> selecionarOrfas(Connection conn, long orfaMs, int limite) throws SQLException {
        String sql = "SELECT user_id, idempotency_key, event_id, hold_id, saga_status, "
                + "(extract(epoch FROM (now() - updated_at)) * 1000)::bigint FROM reservation.idempotency_key "
                + "WHERE saga_status IN ('reserving', 'compensating') AND updated_at < now() - make_interval(secs => ?) "
                + "ORDER BY updated_at LIMIT ? FOR UPDATE SKIP LOCKED";
        List<Orfa> orfas = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, orfaMs / 1000.0);
            ps.setInt(2, limite);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    orfas.add(new Orfa(rs.getLong(1), rs.getString(2), rs.getLong(3), (UUID) rs.getObject(4), rs.getString(5), rs.getLong(6)));
                }
            }
        }
        return orfas;
    }

    /** {@link #paraCompensando(String, Long, UUID)} na transação do chamador (o reaper, que já segura a linha com {@code FOR UPDATE}). */
    public boolean paraCompensando(Connection conn, String key, Long userId, UUID holdId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE reservation.idempotency_key SET saga_status = 'compensating', "
                + "updated_at = now() WHERE " + PK + " AND hold_id = ? AND saga_status = 'reserving'")) {
            ps.setLong(1, userId);
            ps.setString(2, key);
            ps.setObject(3, holdId);
            return ps.executeUpdate() == 1;
        }
    }

    /** {@code compensating -> compensated} depois do {@code release} com 200. */
    public boolean compensada(String key, Long userId, UUID holdId, String resolvidaPor) {
        String sql = "UPDATE reservation.idempotency_key SET saga_status = 'compensated', resolved_at = now(), resolved_by = ?, "
                + "updated_at = now() WHERE " + PK + " AND hold_id = ? AND saga_status = 'compensating'";
        return executar(sql, ps -> {
            ps.setString(1, resolvidaPor);
            ps.setLong(2, userId);
            ps.setString(3, key);
            ps.setObject(4, holdId);
        }) == 1;
    }

    /**
     * Apaga até {@code lote} chaves em ESTADO TERMINAL ({@code reserved}, {@code compensated}) com {@code created_at} mais antigo que
     * {@code retencaoHoras}; nunca {@code reserving} nem {@code compensating}, que são sagas em curso (#47). Um único {@code DELETE}
     * limitado (CTE + junção por {@code ctid}, o idioma do manual do PostgreSQL para "DELETE com LIMIT"): o chamador repete até devolver
     * menos que {@code lote}, assim nenhuma instrução segura locks em tudo de uma vez. O {@code FOR UPDATE} trava só as linhas do lote.
     * A junção por {@code ctid} é estável porque o {@code ctid} não muda entre o {@code SELECT} e o {@code DELETE} (a linha travada não
     * é atualizada por outra transação).
     * @return quantas linhas foram apagadas nesta chamada.
     */
    public int deleteOlderThan(int retencaoHoras, int lote) {
        String sql = "WITH lote AS (SELECT ctid FROM reservation.idempotency_key "
                + "WHERE created_at < now() - make_interval(hours => ?) AND saga_status IN ('reserved', 'compensated') "
                + "LIMIT ? FOR UPDATE) "
                + "DELETE FROM reservation.idempotency_key d USING lote WHERE d.ctid = lote.ctid";
        return executar(sql, ps -> {
            ps.setInt(1, retencaoHoras);
            ps.setInt(2, lote);
        });
    }

    private boolean transitar(String sql, String key, Long userId, UUID holdId) {
        return executar(sql, ps -> {
            ps.setLong(1, userId);
            ps.setString(2, key);
            ps.setObject(3, holdId);
        }) == 1;
    }

    private int atualizar(String sql, String key, Long userId, Long eventId, UUID holdId) {
        return executar(sql, ps -> {
            ps.setString(1, key);
            ps.setLong(2, userId);
            ps.setLong(3, eventId);
            ps.setObject(4, holdId);
        });
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private int executar(String sql, Binder binder) {
        try (Connection conn = databaseConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException("Erro na saga da Idempotency-Key: " + ex.getMessage(), ex);
        }
    }
}
