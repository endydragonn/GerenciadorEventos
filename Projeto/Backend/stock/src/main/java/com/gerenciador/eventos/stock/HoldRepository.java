package com.gerenciador.eventos.stock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

/**
 * SQL do ledger {@code stock.hold}. Tudo na {@link Connection}/transação do chamador ({@link HoldService}, {@link StockReleaseConsumer}):
 * o hold e o débito/devolução do {@code eventcap} são atômicos. Estados: {@code held} (vaga presa) e {@code released} (devolvida, ou
 * LÁPIDE de um hold que nunca existiu). Um hold liberado nunca volta a {@code held}.
 */
@Repository
public class HoldRepository {

    /** Hold que passou de {@code held} para {@code released} (ou a lápide, com {@code decremented = false}). */
    public record Liberado(long eventId, boolean decremented) { }

    /**
     * {@code INSERT ... ON CONFLICT (hold_id) DO NOTHING}. Se outra transação ainda tem o mesmo {@code hold_id} sem commit, o statement
     * ESPERA o desfecho dela.
     * @return {@code true} se criou o hold; {@code false} se já existia (repetição ou lápide)
     */
    public boolean inserirHeld(Connection conn, UUID holdId, long eventId, long userId, boolean decremented) throws SQLException {
        String sql = "INSERT INTO stock.hold (hold_id, event_id, user_id, decremented, status) VALUES (?, ?, ?, ?, 'held') "
                + "ON CONFLICT (hold_id) DO NOTHING";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, holdId);
            stmt.setLong(2, eventId);
            stmt.setLong(3, userId);
            stmt.setBoolean(4, decremented);
            return stmt.executeUpdate() == 1;
        }
    }

    /** @return {@code held}, {@code released}, ou {@code null} se o hold não existe */
    public String status(Connection conn, UUID holdId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT status FROM stock.hold WHERE hold_id = ?")) {
            stmt.setObject(1, holdId);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /**
     * Libera o hold num UPSERT de UM statement só. Por que não {@code UPDATE ... WHERE status = 'held'} seguido de um
     * {@code INSERT} da lápide: em READ COMMITTED o {@code UPDATE} não vê o {@code INSERT} ainda sem commit de um "reservar" em voo e
     * devolveria 0 linhas; a lápide {@code ON CONFLICT DO NOTHING} esperaria e não faria nada, e o hold comitaria {@code held} com a
     * saga já compensada (vaga presa para sempre). Com o upsert o statement ESPERA a transação do "reservar": se ela comitar, libera e
     * devolve o débito; se fizer rollback ("lotado"), grava a lápide.
     * @return o hold que estava {@code held} (com o débito a devolver), ou vazio se já estava {@code released}. O INSERT que não
     *         conflita (lápide) devolve {@code decremented = false}: nada a devolver.
     */
    public Optional<Liberado> liberar(Connection conn, UUID holdId, long eventId, long userId) throws SQLException {
        String sql = "INSERT INTO stock.hold (hold_id, event_id, user_id, decremented, status) VALUES (?, ?, ?, false, 'released') "
                + "ON CONFLICT (hold_id) DO UPDATE SET status = 'released', updated_at = now() WHERE stock.hold.status = 'held' "
                + "RETURNING event_id, decremented";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, holdId);
            stmt.setLong(2, eventId);
            stmt.setLong(3, userId);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? Optional.of(new Liberado(rs.getLong(1), rs.getBoolean(2))) : Optional.empty();
        }
    }

    /**
     * Libera um lote de holds (consumer de cancelamento/expiração): só os que estão {@code held}. Sem lápide: o hold existe antes do
     * evento que o referencia ser publicado.
     */
    public List<Liberado> liberarLote(Connection conn, Collection<UUID> holdIds) throws SQLException {
        String sql = "UPDATE stock.hold SET status = 'released', updated_at = now() "
                + "WHERE hold_id = ANY(?) AND status = 'held' RETURNING event_id, decremented";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setArray(1, conn.createArrayOf("uuid", holdIds.toArray()));
            ResultSet rs = stmt.executeQuery();
            List<Liberado> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Liberado(rs.getLong(1), rs.getBoolean(2)));
            }
            return out;
        }
    }
}
