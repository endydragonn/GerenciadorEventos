package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Leitura da disponibilidade para o caminho "ingênuo" de reserva ({@code reservation.readpath=naive}).
 *
 * <p>{@code event_availability} é a projeção ASSÍNCRONA (consumer do tópico {@code reservation.wallet},
 * {@link EventAvailabilityProjection}) e guarda só {@code ativos}; a disponibilidade é calculada AQUI, na leitura:
 * {@code eventcap LEFT JOIN} a projeção. A projeção não é fonte de verdade ({@code eventcap} + CAS são), e esta
 * leitura NUNCA usa {@code eventcap.quant} (viraria o CAS): só {@code capacity}.</p>
 */
@Repository
public class EventAvailabilityRepository {

    @Autowired
    private DatabaseConnection databaseConnection;

    /**
     * Disponibilidade defasada do evento: {@code capacity - max(ativos, 0)} (piso 0; {@code -1} = ilimitado; {@code ativos} negativo conta como 0, senão a
     * disponibilidade passaria da capacidade). Um evento
     * sem nenhuma reserva projetada ainda tem valor ({@code capacity}); a mudança de capacidade vale na hora.
     * @return a disponibilidade, ou {@code null} se o evento não tem {@code eventcap}.
     */
    public Integer findAvailable(Long eventId) {
        String sql = "SELECT CASE WHEN ec.capacity = -1 THEN -1 "
                + "            ELSE GREATEST(ec.capacity - GREATEST(COALESCE(ea.ativos, 0), 0), 0) END "
                + "FROM eventcap ec LEFT JOIN event_availability ea ON ea.event_id = ec.event_id "
                + "WHERE ec.event_id = ?";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, eventId);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getInt(1) : null;
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao consultar a disponibilidade: " + ex.getMessage(), ex);
        }
    }
}
