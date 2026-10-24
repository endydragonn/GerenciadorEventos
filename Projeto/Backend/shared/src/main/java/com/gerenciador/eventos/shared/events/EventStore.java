package com.gerenciador.eventos.shared.events;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Append e leitura por stream no {@code event_store} (JDBC puro). O chamador é dono da transação: o append e a
 * linha do outbox entram na {@link Connection} que ele passa e só valem com o commit dele.
 */
@Component
public class EventStore {

    /** Colunas do envelope, na ordem que {@link #envelope(ResultSet)} lê (reutilizada pelo relay e pelo replay). */
    static final String COLUNAS = "global_position, event_id, stream_type, stream_id, version, event_type, "
            + "schema_version, occurred_at, payload::text, metadata::text";

    private static final Pattern SCHEMA_VALIDO = Pattern.compile("[a-z_][a-z0-9_]*");

    private final ObjectMapper json;
    private final String schema;
    private final String append;

    /**
     * @param schema schema DESTE serviço no banco ({@code events.schema}, default {@code public}): nele moram o
     *               {@code event_store}, o {@code event_outbox}, o {@code event_snapshot}, o {@code projection_checkpoint} e as
     *               projeções vivas. Cada serviço tem o seu (Fase 7): nenhum serviço lê a tabela de outro.
     */
    public EventStore(ObjectMapper json, @Value("${events.schema:public}") String schema) {
        if (!SCHEMA_VALIDO.matcher(schema).matches()) {
            throw new IllegalArgumentException("events.schema inválido: '" + schema + "'");
        }
        this.json = json;
        this.schema = schema;
        // Uma ida ao banco: o INSERT (ON CONFLICT DO NOTHING) e o do outbox só acontecem se o primeiro gravou a linha.
        // Sem linha de volta = conflito de versão. O CTE do outbox roda mesmo sem ser referenciado no SELECT final.
        this.append = "WITH ins AS ("
                + "INSERT INTO " + tabela("event_store") + " (event_id, stream_type, stream_id, version, event_type, "
                + "schema_version, payload, metadata, partition_key) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?) "
                + "ON CONFLICT (stream_type, stream_id, version) DO NOTHING "
                + "RETURNING global_position, occurred_at), "
                + "ob AS (INSERT INTO " + tabela("event_outbox") + " (global_position) SELECT global_position FROM ins) "
                + "SELECT global_position, occurred_at FROM ins";
    }

    /** Schema do serviço (ver o construtor). */
    public String schema() {
        return schema;
    }

    /** Nome qualificado de uma tabela do schema do serviço (o Supavisor em transaction mode proíbe {@code search_path}). */
    public String tabela(String nome) {
        return schema + "." + nome;
    }

    /**
     * Appenda o evento na transação da {@code conn}. Devolve o envelope gravado, ou vazio se a versão já existe
     * (outra transação gravou antes): é o conflito otimista, sem {@code unique_violation}, então a transação do
     * chamador segue utilizável e ele decide (rollback + resposta da corrida, ou repetir o command).
     */
    public Optional<EventEnvelope> append(Connection conn, NovoEvento e) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(append)) {
            ps.setObject(1, e.eventId());
            ps.setString(2, e.streamType());
            ps.setString(3, e.streamId());
            ps.setInt(4, e.version());
            ps.setString(5, e.eventType());
            ps.setInt(6, e.schemaVersion());
            ps.setString(7, e.payload().toString());
            ps.setString(8, e.metadata().toString());
            ps.setString(9, e.partitionKey());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new EventEnvelope(e.eventId(), e.streamType(), e.streamId(), e.version(),
                        rs.getLong(1), e.eventType(), e.schemaVersion(),
                        rs.getObject(2, OffsetDateTime.class).toInstant(), e.payload(), e.metadata()));
            }
        }
    }

    /** Eventos do stream em ordem de {@code version}, a partir de {@code fromVersion} (inclusive). */
    public List<EventEnvelope> readStream(Connection conn, String streamType, String streamId, int fromVersion)
            throws SQLException {
        return readRange(conn, streamType, streamId, fromVersion, Integer.MAX_VALUE);
    }

    /** Eventos do stream com {@code fromVersion <= version <= toVersion}, em ordem de {@code version}. */
    List<EventEnvelope> readRange(Connection conn, String streamType, String streamId, int fromVersion,
            int toVersion) throws SQLException {
        String sql = "SELECT " + COLUNAS + " FROM " + tabela("event_store") + " "
                + "WHERE stream_type = ? AND stream_id = ? AND version >= ? AND version <= ? ORDER BY version";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, streamType);
            ps.setString(2, streamId);
            ps.setInt(3, fromVersion);
            ps.setInt(4, toVersion);
            return lerEnvelopes(ps);
        }
    }

    /** Executa a consulta (de {@link #COLUNAS}) e lê todos os envelopes; o chamador fecha o {@code ps}. */
    List<EventEnvelope> lerEnvelopes(PreparedStatement ps) throws SQLException {
        List<EventEnvelope> lista = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lista.add(envelope(rs));
            }
        }
        return lista;
    }

    /** Lê o envelope nas colunas de {@link #COLUNAS}. */
    EventEnvelope envelope(ResultSet rs) throws SQLException {
        try {
            return new EventEnvelope(rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getInt(5),
                    rs.getLong(1), rs.getString(6), rs.getInt(7),
                    rs.getObject(8, OffsetDateTime.class).toInstant(),
                    json.readTree(rs.getString(9)), json.readTree(rs.getString(10)));
        } catch (JsonProcessingException ex) {
            throw new SQLException("JSON inválido no event_store", ex);
        }
    }
}
