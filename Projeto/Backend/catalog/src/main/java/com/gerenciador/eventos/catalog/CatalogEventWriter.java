package com.gerenciador.eventos.catalog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gerenciador.eventos.catalogclient.api.EventoEmConflitoException;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventEnvelope;
import com.gerenciador.eventos.shared.events.EventStore;
import com.gerenciador.eventos.shared.events.NovoEvento;

/**
 * Command side do agregado {@code Event} (stream {@code catalog.event}, issue #28). Cada command roda numa transação
 * JDBC manual (como o resto do projeto): trava a linha de {@code event}, lê a {@code version} dela (= versão do último
 * evento aplicado, projeção inline), appenda a versão seguinte no {@code event_store} e aplica a
 * {@link CatalogEventProjection} na MESMA {@link Connection}.
 *
 * <p><b>Conflito otimista:</b> {@code append} devolvendo vazio = outra transação gravou aquela versão. A transação é
 * desfeita e o command REPETIDO inteiro, relendo a versão ({@link #MAX_TENTATIVAS} tentativas). Como a linha de
 * {@code event} fica travada ({@code FOR NO KEY UPDATE}, que não conflita com o {@code KEY SHARE} das FKs de
 * {@code eventcap}/{@code walletevent}) do read ao commit, commands concorrentes no mesmo evento se serializam e o
 * conflito só aparece se alguém appendar no stream por fora da projeção; na prática o retry só ajuda quando o conflito é
 * transitório (ex.: {@code novoStream}), não em evento já existente.</p>
 *
 * <p>Evento com {@code version = 0} (pré-ES, sem stream) é RECUSADO por {@link Tx#bloquear}: o backfill é da migração.</p>
 *
 * <p>Snapshot ({@code EventSnapshots}) não é usado: o estado e a versão do agregado já estão na projeção inline, nenhum
 * command reconstrói estado lendo o stream.</p>
 */
@Component
public class CatalogEventWriter {

    static final int MAX_TENTATIVAS = 5;

    @Autowired
    private DatabaseConnection db;

    @Autowired
    private EventStore store;

    @Autowired
    private CatalogEventProjection projection;

    /** Corpo de um command: roda dentro da transação e appenda por {@link Tx#append}. */
    @FunctionalInterface
    public interface Command<T> {
        T run(Tx tx) throws SQLException;
    }

    /** Alguém appendou a versão esperada antes: refazer o command numa transação nova. */
    private static final class ConflitoDeVersao extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ConflitoDeVersao() {
            super(null, null, false, false);
        }
    }

    /** Transação de um command sobre um evento. */
    public final class Tx {
        private final Connection conn;
        private long eventId;
        private int version;

        private Tx(Connection conn) {
            this.conn = conn;
        }

        public Connection conn() {
            return conn;
        }

        /** Reserva o id do próximo evento (sequence da identity): stream novo, versão 0. */
        public long novoStream() throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT nextval(pg_get_serial_sequence('catalog.event', 'event_id'))");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                eventId = rs.getLong(1);
                version = 0;
                return eventId;
            }
        }

        /** Trava a linha do evento e lê a versão. {@code false} = o evento não existe; lança se for pré-ES (version 0). */
        public boolean bloquear(long id) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT version FROM catalog.event WHERE event_id = ? FOR NO KEY UPDATE")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    eventId = id;
                    version = rs.getInt(1);
                    if (version == 0) { // pré-ES: sem EventCreated, um append aqui deixaria um stream órfão a partir da v1
                        throw new EventoEmConflitoException("Evento " + id + " é pré-ES (version = 0, sem stream): aplique "
                                + "db/migrations/20261022-catalog-event-version.sql (backfill) antes de alterá-lo");
                    }
                    return true;
                }
            }
        }

        /** Appenda o evento na próxima versão do stream e o aplica na projeção inline. */
        public void append(String eventType, ObjectNode payload) throws SQLException {
            String id = String.valueOf(eventId);
            EventEnvelope env = store.append(conn,
                    NovoEvento.of(CatalogEventProjection.STREAM_TYPE, id, version + 1, eventType, payload, id))
                    .orElseThrow(ConflitoDeVersao::new);
            projection.apply(conn, env, store.schema());
            version = env.version();
        }
    }

    /** Roda o command numa transação, repetindo-o inteiro em conflito de versão. */
    public <T> T executar(Command<T> command) throws SQLException {
        for (int tentativa = 1;; tentativa++) {
            try (Connection conn = db.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    T resultado = command.run(new Tx(conn));
                    conn.commit();
                    return resultado;
                } catch (ConflitoDeVersao ex) {
                    conn.rollback();
                    if (tentativa >= MAX_TENTATIVAS) {
                        throw new EventoEmConflitoException(
                                "Conflito de versão no stream catalog.event após " + MAX_TENTATIVAS + " tentativas");
                    }
                } catch (SQLException | RuntimeException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }
    }

    /**
     * Troca a lista de apresentadores do evento: um {@code PresenterRemoved}/{@code PresenterAdded} por diferença
     * (nomes aparados, sem vazios nem repetidos). Sem diferença não appenda nada.
     */
    public void trocarApresentadores(long eventId, List<String> novos) {
        Set<String> desejados = normalizar(novos);
        try {
            executar(tx -> {
                if (!tx.bloquear(eventId)) {
                    return null;
                }
                Set<String> atuais = new LinkedHashSet<>();
                try (PreparedStatement ps = tx.conn().prepareStatement(
                        "SELECT presenter_name FROM catalog.eventpresenter WHERE event_id = ? ORDER BY presenter_name")) {
                    ps.setLong(1, eventId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            atuais.add(rs.getString(1));
                        }
                    }
                }
                for (String nome : atuais) {
                    if (!desejados.contains(nome)) {
                        tx.append("PresenterRemoved", EventPayloads.apresentador(eventId, nome));
                    }
                }
                for (String nome : desejados) {
                    if (!atuais.contains(nome)) {
                        tx.append("PresenterAdded", EventPayloads.apresentador(eventId, nome));
                    }
                }
                return null;
            });
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao gravar apresentadores: " + ex.getMessage(), ex);
        }
    }

    /** Nomes aparados, sem vazios nem repetidos, na ordem de entrada. */
    static Set<String> normalizar(List<String> nomes) {
        Set<String> unicos = new LinkedHashSet<>();
        for (String n : nomes == null ? new ArrayList<String>() : nomes) {
            if (n != null && !n.isBlank()) {
                unicos.add(n.trim());
            }
        }
        return unicos;
    }
}
