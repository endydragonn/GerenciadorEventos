package com.gerenciador.eventos.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.gerenciador.eventos.config.DatabaseConnection;

/**
 * Repository JDBC do read model desnormalizado public.event_availability.
 *
 * <p>Esta tabela NÃO é fonte de verdade — {@code eventcap} + CAS continuam sendo. Ela é
 * recalculada só pelo job {@link com.gerenciador.eventos.Service.ReadModelRefreshService}
 * num timer (lag configurável) e serve o "caminho ingênuo" de reserva
 * ({@code EventWalletService}, feature flag {@code reservation.readpath=naive}), braço de
 * controle do experimento de concorrência da Fase 2.</p>
 */
@Repository
public class EventAvailabilityRepository {

    @Autowired
    private DatabaseConnection databaseConnection;

    /**
     * Recalcula TODAS as linhas do read model a partir do estado atual de
     * {@code eventcap}/{@code walletevent}, num único UPSERT.
     * {@code available = capacity - reservas ativas} (piso 0); {@code -1} = evento ilimitado.
     * @return nº de linhas inseridas/atualizadas.
     */
    public int refreshAll() {
        String sql = "INSERT INTO event_availability (event_id, available, refreshed_at) "
                + "SELECT ec.event_id, "
                + "       CASE WHEN ec.capacity = -1 THEN -1 "
                + "            ELSE GREATEST(ec.capacity - COALESCE(a.ativos, 0), 0) END, "
                + "       now() "
                + "FROM eventcap ec "
                + "LEFT JOIN ( "
                + "    SELECT event_id, count(*) AS ativos "
                + "    FROM walletevent "
                + "    WHERE status IN ('pending', 'confirmed') "
                + "    GROUP BY event_id "
                + ") a ON a.event_id = ec.event_id "
                + "ON CONFLICT (event_id) DO UPDATE "
                + "  SET available = EXCLUDED.available, refreshed_at = EXCLUDED.refreshed_at";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            return stmt.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao atualizar event_availability: " + ex.getMessage(), ex);
        }
    }

    /**
     * Disponibilidade defasada do evento no read model.
     * @return {@code available} (piso 0; {@code -1} = ilimitado), ou {@code null} se o job
     *         ainda não calculou a linha para este evento.
     */
    public Integer findAvailable(Long eventId) {
        String sql = "SELECT available FROM event_availability WHERE event_id = ?";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, eventId);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getInt(1) : null;
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao consultar event_availability: " + ex.getMessage(), ex);
        }
    }
}
