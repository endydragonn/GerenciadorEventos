package com.gerenciador.eventos.stock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.gerenciador.eventos.stockclient.api.EventCap;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Repository JDBC do agregado isolado {@code stock.eventcap} (schema do serviço stock).
 *
 * <p>A reserva de vaga é um UPDATE condicional atômico (CAS a nível de linha): a corretude é garantida pelo próprio banco, sem SELECT
 * prévio, sem lock aberto e sem retry. {@link #tryReserve} e {@link #devolver} recebem a {@link Connection} da transação do
 * {@link HoldService} (o hold e o débito/devolução são atômicos). A diferença para o antigo {@code reservation.eventcap}: quem sabe
 * se uma reserva está ativa é o ledger {@code stock.hold}, não {@code walletevent} (outro schema, de outro serviço).</p>
 */
@Repository
public class EventCapRepository {

    private static final Logger log = LoggerFactory.getLogger(EventCapRepository.class);

    @Autowired
    private DatabaseConnection databaseConnection;

    /**
     * Cria o agregado de capacidade do evento, IDEMPOTENTE por {@code event_id}: se já existe não altera nada e devolve {@code false}.
     * É o que permite a criação síncrona (chamada do catalog) e a reconciliação pelo {@code EventCreated} coexistirem.
     * @return {@code true} se criou; {@code false} se o evento já tinha {@code eventcap}.
     */
    public boolean create(Connection conn, EventCap cap) throws SQLException {
        String sql = "INSERT INTO stock.eventcap (event_id, capacity, quant, buy_time_limit, max_time_in_line) "
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
     * Remove o {@code eventcap} do evento que está sendo apagado no catalog, a menos que exista hold do evento em QUALQUER status (o
     * antigo {@code ON DELETE RESTRICT}). Checagem e {@code DELETE} são um só statement, mas não travam os holds: um hold que nasça
     * entre os dois passos escapa (janela de milissegundos, só se alguém reservar um evento que está sendo apagado). Reserva nova de
     * evento sem {@code eventcap} já é barrada.
     * @return {@code true} se apagou ou se não havia {@code eventcap}; {@code false} se há hold (nada foi apagado).
     */
    public boolean deleteIfNoReservations(Long eventId) {
        String sql = "DELETE FROM stock.eventcap WHERE event_id = ? "
                + "AND NOT EXISTS (SELECT 1 FROM stock.hold h WHERE h.event_id = ?)";
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
        try (Connection conn = databaseConnection.getConnection()) {
            return findByEventId(conn, eventId);
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao buscar eventcap: " + ex.getMessage(), ex);
        }
    }

    /** Leitura simples (sem lock) na conexão do chamador: o "reservar" lê a política dentro da própria transação. */
    public EventCap findByEventId(Connection conn, Long eventId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM stock.eventcap WHERE event_id = ?")) {
            stmt.setLong(1, eventId);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? map(rs) : null;
        }
    }

    /** Buscar os agregados de capacidade de vários eventos numa só consulta (event_id -> cap). */
    public Map<Long, EventCap> findByEventIds(Collection<Long> eventIds) {
        Map<Long, EventCap> out = new HashMap<>();
        if (eventIds == null || eventIds.isEmpty()) return out;
        String sql = "SELECT * FROM stock.eventcap WHERE event_id = ANY(?)";
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
     * Atualiza a política de capacidade/venda do evento. A nova quantidade disponível acompanha a variação de capacidade
     * ({@code quant += novaCapacidade - capacidadeAtual}); se isso deixaria {@code quant} negativo (reduzir abaixo do que já foi
     * vendido), a operação é rejeitada ({@code IllegalArgumentException}, nada muda).
     *
     * <p>Ilimitado -> limitado (issue #56): {@code quant = novaCapacidade - ativas}, com {@code ativas} = holds {@code held} do evento
     * no ledger {@code stock.hold}. Na MESMA transação e ANTES do {@code UPDATE} do {@code eventcap} (mesma ordem de lock do
     * "reservar": hold e depois {@code eventcap}), os holds {@code held} são marcados {@code decremented = true}: a conversão passa a
     * contá-los no {@code quant}, e sem a marca o cancelamento deles nunca devolveria a vaga (perda permanente). Limitado ->
     * ilimitado não mexe nos holds: o release de evento ilimitado não toca o {@code eventcap}.</p>
     *
     * <p>O {@code count} conta só {@code held AND decremented}: o {@code marcar} e o {@code UPDATE} são statements separados (snapshots
     * diferentes em READ COMMITTED), e um hold que comite entre os dois não é marcado. Contá-lo faria o {@code quant} ficar 1 abaixo de
     * {@code capacity - held debitados} e o cancelamento dele nunca devolveria a vaga (perda permanente); sem contar, ele cai na janela
     * de overselling abaixo.</p>
     *
     * <p><b>Deadlock raro (documentado):</b> o {@code marcar} trava os holds do evento na ordem do índice {@code (event_id, status)},
     * e um lote do {@link StockReleaseConsumer} os trava pela PK {@code hold_id}. Com holds em comum e ordens opostas o Postgres aborta
     * um dos dois: o consumer reentrega o lote (idempotente, sem perda); o PATCH da política vira 500 e o chamador repete.</p>
     *
     * <p><b>Janela de corrida (documentada, não tratada):</b> a reserva de evento ilimitado não trava a linha de {@code eventcap}. Um
     * hold em voo cujo commit venha DEPOIS do {@code count} deste statement não é contado e {@code quant} fica acima do real
     * (overselling de no máximo os holds em voo na conversão). O caso inverso é o lado seguro: um hold {@code held} cujo cancelamento
     * ainda não foi consumido conta como ativo (uma vaga a menos, nunca a mais).</p>
     * @return {@code false} se o evento não tem {@code eventcap} (nada atualizado)
     */
    public boolean updatePolicy(Long eventId, Integer newCapacity, LocalDateTime buyTimeLimit) {
        String marcar = "UPDATE stock.hold SET decremented = true, updated_at = now() WHERE event_id = ? AND status = 'held' "
                + "AND NOT decremented AND EXISTS (SELECT 1 FROM stock.eventcap WHERE event_id = ? AND capacity = -1)";
        // quant novo: nova capacidade -1 -> -1; era ilimitado e virou limitado -> nova menos os holds ativos; limitado -> limitado acompanha a variação
        String sql = "UPDATE stock.eventcap "
                + "SET capacity = ?, "
                + "    quant = CASE WHEN ? = -1 THEN -1 "
                + "                 WHEN capacity = -1 THEN ? - (SELECT count(*) FROM stock.hold h "
                + "                      WHERE h.event_id = eventcap.event_id AND h.status = 'held' AND h.decremented) "
                + "                 ELSE quant + (? - capacity) END, "
                + "    buy_time_limit = ?, "
                + "    updated_at = CURRENT_TIMESTAMP "
                + "WHERE event_id = ?";
        try (Connection conn = databaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (newCapacity != EventCap.UNLIMITED) {
                    try (PreparedStatement stmt = conn.prepareStatement(marcar)) {
                        stmt.setLong(1, eventId);
                        stmt.setLong(2, eventId);
                        stmt.executeUpdate();
                    }
                }
                boolean atualizou;
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
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
                    atualizou = stmt.executeUpdate() == 1;
                }
                conn.commit();
                return atualizou;
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            if (isCheckViolation(ex)) {
                throw new IllegalArgumentException("Capacidade menor que o número de inscrições já feitas");
            }
            throw new RuntimeException("Erro ao atualizar eventcap: " + ex.getMessage(), ex);
        }
    }

    /**
     * Reserva uma vaga via UPDATE condicional atômico. Só se aplica a eventos LIMITADOS: para {@code capacity = -1} esta query não
     * afeta linha alguma e o chamador trata a reserva como sempre permitida.
     * @return {@code true} se decrementou (havia vaga); {@code false} se lotado (ou ilimitado).
     */
    public boolean tryReserve(Connection conn, Long eventId) throws SQLException {
        String sql = "UPDATE stock.eventcap SET quant = quant - 1, updated_at = CURRENT_TIMESTAMP "
                + "WHERE event_id = ? AND capacity <> -1 AND quant >= 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, eventId);
            return stmt.executeUpdate() == 1;
        }
    }

    /**
     * Devolve {@code n} vagas ({@code quant = LEAST(quant + n, capacity)}), na transação do chamador, como ÚLTIMO comando antes do
     * commit (a linha quente fica travada o mínimo). Não faz nada para evento ilimitado nem sem {@code eventcap}. Com o ledger de
     * holds o teto nunca deveria cortar nada: se cortar, é inconsistência (log WARN) e o {@code quant} vai a {@code capacity}, sem
     * violar o CHECK (um CHECK violado no consumer travaria a partição).
     */
    public void devolver(Connection conn, Long eventId, int n) throws SQLException {
        String sql = "UPDATE stock.eventcap SET quant = quant + ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE event_id = ? AND capacity <> -1 AND quant + ? <= capacity";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, n);
            stmt.setLong(2, eventId);
            stmt.setInt(3, n);
            if (stmt.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement stmt = conn.prepareStatement("UPDATE stock.eventcap SET quant = capacity, "
                + "updated_at = CURRENT_TIMESTAMP WHERE event_id = ? AND capacity <> -1 AND quant < capacity")) {
            stmt.setLong(1, eventId);
            if (stmt.executeUpdate() == 1) {
                log.warn("[stock] devolução de {} vaga(s) do evento {} passaria da capacidade: quant limitado a capacity "
                        + "(o ledger de holds e o eventcap divergiram)", n, eventId);
            }
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
