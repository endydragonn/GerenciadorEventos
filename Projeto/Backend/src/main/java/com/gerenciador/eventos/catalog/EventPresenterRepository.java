package com.gerenciador.eventos.catalog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Repository JDBC da tabela filha public.eventpresenter (0..N apresentadores por evento).
 */
@Repository
public class EventPresenterRepository {

    @Autowired
    private DatabaseConnection databaseConnection;

    /** Lista os nomes de apresentadores de um evento. */
    public List<String> findByEventId(Long eventId) {
        String sql = "SELECT presenter_name FROM eventpresenter WHERE event_id = ? ORDER BY presenter_name";
        List<String> out = new ArrayList<>();
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, eventId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao listar apresentadores: " + ex.getMessage(), ex);
        }
    }

    /** Apresentadores de vários eventos numa só consulta (event_id -> nomes, ordenados). */
    public Map<Long, List<String>> findByEventIds(Collection<Long> eventIds) {
        Map<Long, List<String>> out = new HashMap<>();
        if (eventIds == null || eventIds.isEmpty()) return out;
        String sql = "SELECT event_id, presenter_name FROM eventpresenter WHERE event_id = ANY(?) "
                + "ORDER BY event_id, presenter_name";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setArray(1, conn.createArrayOf("bigint", eventIds.toArray()));
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
            }
            return out;
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao listar apresentadores em lote: " + ex.getMessage(), ex);
        }
    }

    /**
     * Substitui toda a lista de apresentadores do evento (delete + insert numa transação).
     * Nomes vazios/duplicados são ignorados.
     */
    public void replaceAll(Long eventId, List<String> presenters) {
        try (Connection conn = databaseConnection.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                replaceAll(conn, eventId, presenters);
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao gravar apresentadores: " + ex.getMessage(), ex);
        }
    }

    /** Variante que participa da transação do chamador (usada no save do evento). */
    public void replaceAll(Connection conn, Long eventId, List<String> presenters) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM eventpresenter WHERE event_id = ?")) {
            del.setLong(1, eventId);
            del.executeUpdate();
        }
        Set<String> unique = new LinkedHashSet<>();
        if (presenters != null) {
            for (String p : presenters) {
                if (p != null && !p.isBlank()) unique.add(p.trim());
            }
        }
        if (unique.isEmpty()) return;
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO eventpresenter (event_id, presenter_name) VALUES (?, ?)")) {
            for (String name : unique) {
                ins.setLong(1, eventId);
                ins.setString(2, name);
                ins.addBatch();
            }
            ins.executeBatch();
        }
    }

    public void deleteByEventId(Long eventId) {
        String sql = "DELETE FROM eventpresenter WHERE event_id = ?";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, eventId);
            stmt.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao apagar apresentadores: " + ex.getMessage(), ex);
        }
    }
}
