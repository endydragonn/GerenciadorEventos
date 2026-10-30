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
 * {@code capacity - ativos}. Desde a #47 a {@code capacity} NÃO é daqui: vem do serviço stock (o {@link EventWalletService} a lê por
 * HTTP e a passa). A projeção não é fonte de verdade ({@code eventcap} + CAS, no stock, são), e esta leitura NUNCA usa
 * {@code eventcap.quant} (viraria o CAS): só {@code capacity}.</p>
 */
@Repository
public class EventAvailabilityRepository {

    @Autowired
    private DatabaseConnection databaseConnection;

    /**
     * Disponibilidade defasada do evento: {@code capacity - max(ativos, 0)} (piso 0; {@code -1} = ilimitado; {@code ativos} negativo
     * conta como 0, senão a disponibilidade passaria da capacidade). Um evento sem nenhuma reserva projetada ainda tem valor
     * ({@code capacity}); a mudança de capacidade vale na hora.
     * @param capacity a {@code eventcap.capacity} lida do stock ({@code -1} = ilimitado)
     */
    public int findAvailable(Long eventId, int capacity) {
        if (capacity == -1) {
            return -1;
        }
        String sql = "SELECT GREATEST(? - GREATEST(COALESCE((SELECT ativos FROM reservation.event_availability WHERE event_id = ?), 0), 0), 0)";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, capacity);
            stmt.setLong(2, eventId);
            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao consultar a disponibilidade: " + ex.getMessage(), ex);
        }
    }
}
