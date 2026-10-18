package com.gerenciador.eventos.catalog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Repository JDBC (somente leitura) da tabela filha public.eventpresenter (0..N apresentadores por evento).
 * As escritas são eventos do stream catalog.event ({@link CatalogEventWriter}, issue #28).
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
}
