package com.gerenciador.eventos.catalog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 *
 * <p>As ESCRITAS (issue #28) são commands do stream {@code catalog.event}: {@code save}/{@code update}/{@code delete}
 * appendam {@code EventCreated}/{@code EventUpdated}/{@code EventCancelled} e aplicam a projeção inline
 * ({@link CatalogEventProjection}) na mesma transação, via {@link CatalogEventWriter}; as leituras vão direto nas
 * tabelas-projeção.</p>
 */
@Repository
public class EventRepository {

    private static final Logger log = LoggerFactory.getLogger(EventRepository.class);

    @Autowired
    private DatabaseConnection databaseConnection;

    @Autowired
    private EventCapacityApi eventCapacityApi;

    @Autowired
    private CatalogEventWriter writer;

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

        try {
            // Uma transação: EventCreated (v1) no event_store + projeção inline (event, eventpresenter) + eventcap.
            // O id vem da sequence ANTES do append (é o streamId); a projeção insere a linha com ele.
            e.setEvent_id(writer.executar(tx -> {
                long id = tx.novoStream();
                ObjectNode payload = EventPayloads.criado(id, e);
                ArrayNode presenters = payload.putArray("presenters");
                CatalogEventWriter.normalizar(e.getPresenters()).forEach(presenters::add);
                tx.append("EventCreated", payload);
                if (e.getImage_data() != null) { // blob fora do ES: direto na tabela viva
                    gravarImagem(tx.conn(), id, e.getImage_data());
                }
                LocalDateTime buyLimit = (e.getBuy_time_limit() != null) ? e.getBuy_time_limit() : e.getEvent_date();
                eventCapacityApi.create(tx.conn(),
                        new EventCap(id, e.getLot_quantity(), e.getLot_quantity(), buyLimit));
                return id;
            }));
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

    /**
     * Atualizar os metadados do evento (capacidade/janela de venda vão pelo EventCapacityApi): appenda
     * {@code EventUpdated} e aplica a projeção na mesma transação. {@code image_data} (fora do ES) é gravada direto.
     */
    public Event update(Event e) {
        if (e.getEvent_id() == null) {
            throw new IllegalArgumentException("event_id é obrigatório para atualizar");
        }
        long id = Objects.requireNonNull(e.getEvent_id(), "event_id não pode ser nulo");
        try {
            writer.executar(tx -> {
                if (!tx.bloquear(id)) {
                    throw new RuntimeException("Evento não encontrado: id=" + id);
                }
                tx.append("EventUpdated", EventPayloads.evento(id, e));
                gravarImagem(tx.conn(), id, e.getImage_data());
                return null;
            });
            fetchTimestamps(e);
            return e;
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao atualizar evento: " + ex.getMessage(), ex);
        }
    }

    /**
     * Remover evento: appenda {@code EventCancelled} e a projeção faz o mesmo {@code DELETE} de antes (eventcap e
     * eventpresenter caem por ON DELETE CASCADE) na mesma transação; o stream fica no event_store como auditoria.
     * Evento inexistente = no-op, como antes.
     */
    public void delete(Long id) {
        try {
            writer.executar(tx -> {
                if (tx.bloquear(id)) {
                    tx.append("EventCancelled", EventPayloads.cancelado(id));
                }
                return null;
            });
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao deletar evento: " + ex.getMessage(), ex);
        }
    }

    // ===== Helpers =====
    /** Blob fora do event sourcing: escrito direto na linha viva (o replay e a comparação ignoram a coluna). */
    private void gravarImagem(Connection conn, long id, byte[] imageData) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("UPDATE event SET image_data = ? WHERE event_id = ?")) {
            if (imageData != null) {
                stmt.setBytes(1, imageData);
            } else {
                stmt.setNull(1, java.sql.Types.BINARY);
            }
            stmt.setLong(2, id);
            stmt.executeUpdate();
        }
    }

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
            log.warn("Não foi possível buscar timestamps do evento id={}: {}", e.getEvent_id(), ex.getMessage());
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
}
