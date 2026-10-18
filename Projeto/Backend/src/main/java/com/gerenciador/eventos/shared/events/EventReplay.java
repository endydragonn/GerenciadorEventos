package com.gerenciador.eventos.shared.events;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Replay (ADR Event-Backbone-Event-Store, seção 2): reconstrói uma projeção a partir do {@code event_store} em
 * tabelas-SOMBRA, num schema próprio, e deixa comparar com a projeção viva. Não reconstrói tabela viva no lugar
 * (FKs para {@code event} e o cascade apagariam o estado do CAS, que não vem do log).
 *
 * <p>Não usa {@code TEMP TABLE} (proibido com o Supavisor): é um schema comum criado e derrubado pelo próprio
 * replay, fora do {@code SchemaValidationTest} e do {@code GlobalDbTruncator}. Determinístico: a projeção não chama
 * {@code now()}, os valores vêm no evento. Ordem de aplicação = {@code global_position}, que respeita a ordem de
 * {@code version} dentro de cada stream (a versão n+1 só é calculada depois do commit da n).</p>
 *
 * <p>{@code global_position} é ordem de ALOCAÇÃO, não de commit (um evento de posição menor pode comitar depois de um
 * de posição maior), então paginar por ela em READ COMMITTED, onde cada página lê um snapshot novo, pularia o evento
 * que comita no meio do replay. Por isso a transação do replay é {@code REPEATABLE READ}: todas as páginas leem o
 * MESMO snapshot, e a sombra representa o log como ele estava no início da leitura (o que comitar depois fica para o
 * próximo replay). O nível é definido por {@code SET TRANSACTION} (statement, vale só para esta transação), nunca
 * por {@code conn.setTransactionIsolation}, que vira estado de sessão no PgJDBC.</p>
 */
@Component
public class EventReplay {

    private static final Pattern IDENTIFICADOR = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final int PAGINA_PADRAO = 500;

    private final DatabaseConnection db;
    private final EventStore eventStore;
    private final int pagina;

    @Autowired
    public EventReplay(DatabaseConnection db, EventStore eventStore) {
        this(db, eventStore, PAGINA_PADRAO);
    }

    /** Tamanho de página explícito (os testes usam página pequena para exercitar a paginação). */
    EventReplay(DatabaseConnection db, EventStore eventStore, int pagina) {
        this.db = db;
        this.eventStore = eventStore;
        this.pagina = pagina;
    }

    /**
     * Recria o {@code schema} com a cópia vazia das tabelas da projeção e reaplica todos os eventos do
     * {@code streamType}. Uma transação: se falhar, não sobra schema pela metade.
     */
    public void rebuild(Projection projection, String streamType, String schema) throws SQLException {
        String sombra = identificador(schema);
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                // Tem de ser o PRIMEIRO statement da transação (antes de qualquer consulta).
                st.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
                st.execute("DROP SCHEMA IF EXISTS " + sombra + " CASCADE");
                st.execute("CREATE SCHEMA " + sombra);
                for (String tabela : projection.tables()) {
                    st.execute("CREATE TABLE " + sombra + "." + identificador(tabela)
                            + " (LIKE public." + identificador(tabela) + " INCLUDING ALL)");
                }
                long ultima = 0;
                List<EventEnvelope> lote;
                while (!(lote = proximaPagina(conn, streamType, ultima)).isEmpty()) {
                    for (EventEnvelope e : lote) {
                        projection.apply(conn, e, sombra);
                    }
                    ultima = lote.get(lote.size() - 1).globalPosition();
                }
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** Tabelas da projeção cujo conteúdo na viva (public) difere da sombra em {@code schema}. Vazio = idênticas. */
    public List<String> divergentTables(Projection projection, String schema) throws SQLException {
        String sombra = identificador(schema);
        List<String> divergentes = new ArrayList<>();
        try (Connection conn = db.getConnection(); Statement st = conn.createStatement()) {
            for (String tabela : projection.tables()) {
                String viva = "public." + identificador(tabela);
                String copia = sombra + "." + identificador(tabela);
                String sql = "SELECT (SELECT count(*) FROM (SELECT * FROM " + viva + " EXCEPT ALL SELECT * FROM " + copia
                        + ") a) + (SELECT count(*) FROM (SELECT * FROM " + copia + " EXCEPT ALL SELECT * FROM " + viva
                        + ") b)";
                try (ResultSet rs = st.executeQuery(sql)) {
                    rs.next();
                    if (rs.getLong(1) > 0) {
                        divergentes.add(tabela);
                    }
                }
            }
        }
        return divergentes;
    }

    /** Derruba o schema-sombra. */
    public void drop(String schema) throws SQLException {
        try (Connection conn = db.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + identificador(schema) + " CASCADE");
        }
    }

    private List<EventEnvelope> proximaPagina(Connection conn, String streamType, long depoisDe) throws SQLException {
        String sql = "SELECT " + EventStore.COLUNAS + " FROM public.event_store "
                + "WHERE stream_type = ? AND global_position > ? ORDER BY global_position LIMIT " + pagina;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, streamType);
            ps.setLong(2, depoisDe);
            return eventStore.lerEnvelopes(ps);
        }
    }

    /** Identificador não é parametrizável em SQL: só aceita o que dispensa aspas (e nunca o {@code public}). */
    private static String identificador(String nome) {
        if (!IDENTIFICADOR.matcher(nome).matches() || "public".equals(nome)) {
            throw new IllegalArgumentException("Identificador inválido para o replay: '" + nome + "'");
        }
        return nome;
    }
}
