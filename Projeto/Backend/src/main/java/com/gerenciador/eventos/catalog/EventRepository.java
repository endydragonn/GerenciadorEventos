package com.gerenciador.eventos.catalog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.gerenciador.eventos.catalog.api.Event;
import com.gerenciador.eventos.reservation.api.EventCap;
import com.gerenciador.eventos.reservation.api.EventCapacityApi;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Repository para entidade Event usando JDBC cru via DatabaseConnection.
 *
 * <p>A tabela {@code public.event} guarda só metadados. Capacidade/vagas/janela de venda
 * ficam no agregado isolado {@code public.eventcap} ({@link EventCapacityApi}); a lista de
 * apresentadores fica em {@code public.eventpresenter} ({@link EventPresenterRepository}).
 * O POJO {@link Event} continua carregando {@code lot_quantity}/{@code quantity}/
 * {@code buy_time_limit}/{@code presenters} como campos de transporte da API — este
 * repository os grava/lê no agregado certo.</p>
 */
@Repository
public class EventRepository {

    @Autowired
    private DatabaseConnection databaseConnection;

    @Autowired
    private EventCapacityApi eventCapacityApi;

    @Autowired
    private EventPresenterRepository eventPresenterRepository;

    /**
     * Insere evento + agregado de capacidade + apresentadores numa única transação.
     * {@code lot_quantity} do POJO vira {@code eventcap.capacity}, e {@code quant} nasce
     * igual à capacidade (todas as vagas disponíveis).
     */
    public Event save(Event e) {
        if (e.getCreator_id() == null) {
            throw new IllegalArgumentException("creator_id é obrigatório");
        }
        if (e.getEvent_name() == null || e.getEvent_name().isBlank()) {
            throw new IllegalArgumentException("event_name é obrigatório");
        }
        if (e.getEvent_date() == null) {
            throw new IllegalArgumentException("event_date é obrigatório");
        }
        if (e.getLot_quantity() == null
                || (e.getLot_quantity() < 1 && e.getLot_quantity() != EventCap.UNLIMITED)) {
            throw new IllegalArgumentException("capacity (lot_quantity) é obrigatória: >= 1, ou -1 para ilimitado");
        }

        String sql = "INSERT INTO event (creator_id, event_name, ead, address, event_date, description, image_data) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = databaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    int idx = 1;
                    stmt.setLong(idx++, e.getCreator_id());
                    stmt.setString(idx++, e.getEvent_name());
                    stmt.setBoolean(idx++, toPrimitive(e.getIs_EAD()));
                    stmt.setString(idx++, nullIfBlank(e.getAddress()));
                    stmt.setTimestamp(idx++, toTimestamp(e.getEvent_date()));
                    stmt.setString(idx++, e.getDescription() != null ? e.getDescription() : "");
                    if (e.getImage_data() != null) {
                        stmt.setBytes(idx++, e.getImage_data());
                    } else {
                        stmt.setNull(idx++, java.sql.Types.BINARY);
                    }
                    stmt.executeUpdate();
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        if (rs.next()) {
                            e.setEvent_id(rs.getLong(1));
                        }
                    }
                }

                LocalDateTime buyLimit = (e.getBuy_time_limit() != null) ? e.getBuy_time_limit() : e.getEvent_date();
                EventCap cap = new EventCap(e.getEvent_id(), e.getLot_quantity(), e.getLot_quantity(), buyLimit);
                eventCapacityApi.create(conn, cap);

                eventPresenterRepository.replaceAll(conn, e.getEvent_id(), e.getPresenters());

                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao salvar evento: " + ex.getMessage(), ex);
        }

        // Reflete o estado gravado no POJO retornado
        e.setBuy_time_limit((e.getBuy_time_limit() != null) ? e.getBuy_time_limit() : e.getEvent_date());
        e.setQuantity(e.getLot_quantity());
        fetchTimestamps(e);
        return e;
    }

    /** Buscar evento por ID (metadados apenas; capacidade/apresentadores são carregados pelo Service). */
    public Event findById(Long id) {
        String sql = "SELECT * FROM event WHERE event_id = ?";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return map(rs);
            }
            return null;
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao buscar evento: " + ex.getMessage(), ex);
        }
    }

    /** Buscar por nome (único) */
    public Event findByName(String name) {
        String sql = "SELECT * FROM event WHERE event_name = ?";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return map(rs);
            }
            return null;
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao buscar evento por nome: " + ex.getMessage(), ex);
        }
    }

    /** Verificar se existe evento com mesmo nome */
    public boolean nameExists(String name) {
        String sql = "SELECT COUNT(*) FROM event WHERE event_name = ?";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            ResultSet rs = stmt.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao verificar nome do evento: " + ex.getMessage(), ex);
        }
    }

    /** Listar todos os eventos */
    public List<Event> findAll() {
        String sql = "SELECT * FROM event ORDER BY event_id";
        List<Event> list = new ArrayList<>();
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                list.add(map(rs));
            }
            return list;
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao listar eventos: " + ex.getMessage(), ex);
        }
    }

    /**
     * Busca por nome no banco (substring, case-insensitive), mesma ordem do {@link #findAll()}.
     * {@code %}, {@code _} e {@code \} do termo são tratados literalmente. Termo nulo/em branco
     * devolve todos os eventos.
     */
    public List<Event> searchByName(String term) {
        if (term == null || term.isBlank()) return findAll();
        String sql = "SELECT * FROM event WHERE lower(event_name) LIKE lower(?) ESCAPE '\\' ORDER BY event_id";
        String pattern = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<Event> list = new ArrayList<>();
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, pattern);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                list.add(map(rs));
            }
            return list;
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao buscar eventos por nome: " + ex.getMessage(), ex);
        }
    }

    /** Atualizar apenas os metadados do evento (capacidade/janela de venda vão pelo EventCapacityApi). */
    public Event update(Event e) {
        if (e.getEvent_id() == null) {
            throw new IllegalArgumentException("event_id é obrigatório para atualizar");
        }
        String upSql = "UPDATE event SET creator_id = ?, event_name = ?, ead = ?, address = ?, "
                + "event_date = ?, description = ?, image_data = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE event_id = ?";

        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(upSql)) {

            int idx = 1;
            stmt.setLong(idx++, e.getCreator_id());
            stmt.setString(idx++, e.getEvent_name());
            stmt.setBoolean(idx++, toPrimitive(e.getIs_EAD()));
            stmt.setString(idx++, nullIfBlank(e.getAddress()));
            stmt.setTimestamp(idx++, toTimestamp(e.getEvent_date()));
            stmt.setString(idx++, e.getDescription() != null ? e.getDescription() : "");
            if (e.getImage_data() != null) {
                stmt.setBytes(idx++, e.getImage_data());
            } else {
                stmt.setNull(idx++, java.sql.Types.BINARY);
            }
            long eventIdVal = Objects.requireNonNull(e.getEvent_id(), "event_id não pode ser nulo");
            stmt.setLong(idx++, eventIdVal);

            stmt.executeUpdate();
            fetchTimestamps(e);
            return e;
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao atualizar evento: " + ex.getMessage(), ex);
        }
    }

    /** Remover evento definitivamente (eventcap e eventpresenter caem por ON DELETE CASCADE). */
    public void delete(Long id) {
        String sql = "DELETE FROM event WHERE event_id = ?";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            stmt.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao deletar evento: " + ex.getMessage(), ex);
        }
    }

    // ===== Helpers =====
    private void fetchTimestamps(Event e) {
        if (e.getEvent_id() == null) return;
        String sql = "SELECT created_at, updated_at FROM event WHERE event_id = ?";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, e.getEvent_id());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                Timestamp c = rs.getTimestamp("created_at");
                if (c != null) e.setCreatedAt(c.toLocalDateTime());
                Timestamp u = rs.getTimestamp("updated_at");
                if (u != null) e.setUpdatedAt(u.toLocalDateTime());
            }
        } catch (SQLException ex) {
            System.err.println("Aviso: não foi possível buscar timestamps do evento: " + ex.getMessage());
        }
    }

    private Event map(ResultSet rs) throws SQLException {
        Event e = new Event();
        e.setEvent_id(rs.getLong("event_id"));
        e.setCreator_id(rs.getLong("creator_id"));
        e.setEvent_name(rs.getString("event_name"));
        e.setIs_EAD(rs.getBoolean("ead"));
        e.setAddress(rs.getString("address"));
        Timestamp ed = rs.getTimestamp("event_date");
        if (ed != null) e.setEvent_date(ed.toLocalDateTime());
        e.setDescription(rs.getString("description"));
        byte[] imageData = rs.getBytes("image_data");
        if (imageData != null) e.setImage_data(imageData);
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) e.setCreatedAt(createdAt.toLocalDateTime());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) e.setUpdatedAt(updatedAt.toLocalDateTime());
        // capacity/quant/buy_time_limit/presenters: preenchidos pelo Service a partir dos
        // agregados eventcap / eventpresenter.
        return e;
    }

    private boolean toPrimitive(Boolean b) {
        return Boolean.TRUE.equals(b);
    }

    private Timestamp toTimestamp(LocalDateTime dt) {
        return (dt == null) ? null : Timestamp.valueOf(dt);
    }

    private String nullIfBlank(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
