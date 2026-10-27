package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.gerenciador.eventos.reservationclient.api.EventCap;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Repository JDBC do agregado isolado {@code reservation.eventcap} (schema do serviço reservation).
 *
 * <p>A reserva de vaga é um UPDATE condicional atômico (CAS a nível de linha): a corretude
 * é garantida pelo próprio banco, sem SELECT prévio, sem lock aberto e sem retry.
 * {@link #tryReserve} / {@link #release} recebem a {@link Connection} da transação do serviço
 * chamador para que o decremento e a criação da reserva (ou a devolução da vaga e a marca de
 * expiração) sejam atômicos.</p>
 */
@Repository
public class EventCapRepository implements CapacityReserver {

    @Autowired
    private DatabaseConnection databaseConnection;

    /**
     * Cria o agregado de capacidade do evento, IDEMPOTENTE por {@code event_id}: se já existe não altera nada (nem
     * capacidade, nem vagas) e devolve {@code false}. É o que permite a criação síncrona (chamada do catalog) e a
     * reconciliação pelo {@code EventCreated} do tópico {@code catalog.event} coexistirem: quem chega primeiro vence e
     * a outra é no-op. Usa a conexão/transação do chamador.
     * @return {@code true} se criou; {@code false} se o evento já tinha {@code eventcap}.
     */
    public boolean create(Connection conn, EventCap cap) throws SQLException {
        String sql = "INSERT INTO reservation.eventcap (event_id, capacity, quant, buy_time_limit, max_time_in_line) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING";
        int capacity = cap.getCapacity();
        // ilimitado: quant também vira sentinela -1
        int quant = (capacity == EventCap.UNLIMITED) ? EventCap.UNLIMITED
                : (cap.getQuant() != null ? cap.getQuant() : capacity);
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, cap.getEventId());
            stmt.setInt(2, capacity);
            stmt.setInt(3, quant);
            if (cap.getBuyTimeLimit() != null) {
                stmt.setTimestamp(4, Timestamp.valueOf(cap.getBuyTimeLimit()));
            } else {
                stmt.setNull(4, java.sql.Types.TIMESTAMP);
            }
            stmt.setInt(5, cap.getMaxTimeInLine() != null ? cap.getMaxTimeInLine() : 120);
            return stmt.executeUpdate() == 1;
        }
    }

    /** Variante de {@link #create(Connection, EventCap)} numa conexão própria (autocommit). */
    public boolean create(EventCap cap) {
        try (Connection conn = databaseConnection.getConnection()) {
            return create(conn, cap);
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao criar eventcap: " + ex.getMessage(), ex);
        }
    }

    /**
     * Remove o {@code eventcap} do evento que está sendo apagado no catalog, a menos que exista reserva do evento em
     * QUALQUER status (o antigo {@code ON DELETE RESTRICT} de {@code walletevent}, que sumiu com a FK). A checagem e o
     * {@code DELETE} são um só statement, mas não travam as linhas de {@code walletevent}: uma reserva que nasça entre
     * os dois passos escapa (janela de milissegundos, só se alguém reservar um evento que está sendo apagado). Reserva
     * nova de evento sem {@code eventcap} já é barrada ("Capacidade do evento não configurada").
     * @return {@code true} se apagou ou se não havia {@code eventcap}; {@code false} se há reserva (nada foi apagado).
     */
    public boolean deleteIfNoReservations(Long eventId) {
        String sql = "DELETE FROM reservation.eventcap WHERE event_id = ? "
                + "AND NOT EXISTS (SELECT 1 FROM reservation.walletevent w WHERE w.event_id = ?)";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, eventId);
            stmt.setLong(2, eventId);
            if (stmt.executeUpdate() == 1) {
                return true;
            }
            return findByEventId(eventId) == null;
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao apagar eventcap: " + ex.getMessage(), ex);
        }
    }

    /** Buscar o agregado de capacidade por event_id. */
    public EventCap findByEventId(Long eventId) {
        String sql = "SELECT * FROM reservation.eventcap WHERE event_id = ?";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, eventId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return map(rs);
            }
            return null;
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao buscar eventcap: " + ex.getMessage(), ex);
        }
    }

    /** Buscar os agregados de capacidade de vários eventos numa só consulta (event_id -> cap). */
    public Map<Long, EventCap> findByEventIds(Collection<Long> eventIds) {
        Map<Long, EventCap> out = new HashMap<>();
        if (eventIds == null || eventIds.isEmpty()) return out;
        String sql = "SELECT * FROM reservation.eventcap WHERE event_id = ANY(?)";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setArray(1, conn.createArrayOf("bigint", eventIds.toArray()));
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                EventCap cap = map(rs);
                out.put(cap.getEventId(), cap);
            }
            return out;
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao buscar eventcap em lote: " + ex.getMessage(), ex);
        }
    }

    /**
     * Atualiza a política de capacidade/venda do evento.
     * A nova quantidade disponível acompanha a variação de capacidade
     * ({@code quant += novaCapacidade - capacidadeAtual}); se isso deixaria {@code quant}
     * negativo (reduzir abaixo do que já foi vendido), a operação é rejeitada.
     * Ilimitado -> limitado (issue #56): {@code quant = novaCapacidade - ativas}, com {@code ativas} = reservas
     * {@code pending} + {@code confirmed} do evento contadas no MESMO statement do {@code UPDATE}; capacidade menor
     * que as ativas viola o CHECK e a operação é rejeitada sem alterar nada.
     *
     * <p><b>Janela de corrida (documentada, não tratada):</b> a reserva de evento ilimitado roda sem CAS e não trava
     * a linha de {@code eventcap}. A janela vai da leitura da política (feita fora da transação da reserva) até o commit
     * da reserva: uma reserva em voo cujo commit venha DEPOIS do {@code count} deste statement não é contada, e
     * {@code quant} fica acima do real (overselling de no máximo as reservas em voo na conversão). O caso inverso é o
     * lado seguro: cancelamento ou expiração em voo durante a conversão (já contados como ativos no {@code count}) pode
     * deixar {@code quant} 1 abaixo do real, ou seja, uma vaga a menos, nunca a mais.</p>
     * @return {@code false} se o evento não tem {@code eventcap} (nada atualizado)
     */
    public boolean updatePolicy(Long eventId, Integer newCapacity, LocalDateTime buyTimeLimit) {
        // quant novo:
        //   nova capacidade -1 (ilimitado)           -> -1
        //   era ilimitado e virou limitado           -> nova capacidade menos as reservas ativas (#56)
        //   limitado -> limitado                     -> acompanha a variação da capacidade
        String sql = "UPDATE reservation.eventcap "
                + "SET capacity = ?, "
                + "    quant = CASE WHEN ? = -1 THEN -1 "
                + "                 WHEN capacity = -1 THEN ? - (SELECT count(*) FROM reservation.walletevent w "
                + "                      WHERE w.event_id = eventcap.event_id AND w.status IN ('pending', 'confirmed')) "
                + "                 ELSE quant + (? - capacity) END, "
                + "    buy_time_limit = ?, "
                + "    updated_at = CURRENT_TIMESTAMP "
                + "WHERE event_id = ?";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, newCapacity);
            stmt.setInt(2, newCapacity);
            stmt.setInt(3, newCapacity);
            stmt.setInt(4, newCapacity);
            if (buyTimeLimit != null) {
                stmt.setTimestamp(5, Timestamp.valueOf(buyTimeLimit));
            } else {
                stmt.setNull(5, java.sql.Types.TIMESTAMP);
            }
            stmt.setLong(6, eventId);
            return stmt.executeUpdate() == 1;
        } catch (SQLException ex) {
            if (isCheckViolation(ex)) {
                throw new IllegalArgumentException(
                        "Capacidade menor que o número de inscrições já feitas");
            }
            throw new RuntimeException("Erro ao atualizar eventcap: " + ex.getMessage(), ex);
        }
    }

    /**
     * Reserva uma vaga via UPDATE condicional atômico. Só se aplica a eventos LIMITADOS —
     * para {@code capacity = -1} (ilimitado) esta query não afeta linha alguma e o serviço
     * chamador deve tratar a reserva como sempre permitida.
     * @return {@code true} se decrementou (havia vaga); {@code false} se lotado (ou ilimitado).
     */
    public boolean tryReserve(Connection conn, Long eventId) throws SQLException {
        String sql = "UPDATE reservation.eventcap SET quant = quant - 1, updated_at = CURRENT_TIMESTAMP "
                + "WHERE event_id = ? AND capacity <> -1 AND quant >= 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, eventId);
            return stmt.executeUpdate() == 1;
        }
    }

    /**
     * Devolve uma vaga (cancelamento / expiração de reserva). Usa a transação do chamador.
     * Não faz nada para eventos ilimitados ({@code capacity = -1}).
     */
    public void release(Connection conn, Long eventId) throws SQLException {
        String sql = "UPDATE reservation.eventcap SET quant = quant + 1, updated_at = CURRENT_TIMESTAMP "
                + "WHERE event_id = ? AND capacity <> -1 AND quant < capacity";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, eventId);
            stmt.executeUpdate();
        }
    }

    // ===== Helpers =====
    private EventCap map(ResultSet rs) throws SQLException {
        EventCap cap = new EventCap();
        cap.setEventId(rs.getLong("event_id"));
        cap.setCapacity(rs.getInt("capacity"));
        cap.setQuant(rs.getInt("quant"));
        Timestamp btl = rs.getTimestamp("buy_time_limit");
        if (btl != null) cap.setBuyTimeLimit(btl.toLocalDateTime());
        cap.setMaxTimeInLine(rs.getInt("max_time_in_line"));
        Timestamp c = rs.getTimestamp("created_at");
        if (c != null) cap.setCreatedAt(c.toLocalDateTime());
        Timestamp u = rs.getTimestamp("updated_at");
        if (u != null) cap.setUpdatedAt(u.toLocalDateTime());
        return cap;
    }

    private boolean isCheckViolation(SQLException ex) {
        // 23514 = check_violation no PostgreSQL
        return "23514".equals(ex.getSQLState());
    }
}
