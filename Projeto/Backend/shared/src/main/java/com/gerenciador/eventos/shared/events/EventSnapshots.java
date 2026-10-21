package com.gerenciador.eventos.shared.events;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Snapshots de stream (ADR Event-Backbone-Event-Store, seção 2): guarda o último estado do stream para o agregado
 * reconstruir lendo só a cauda do {@code event_store} ({@code snapshot.version + 1 ..}). Política: a cada
 * {@code events.snapshot.every} eventos do stream (N alto por padrão). É CACHE descartável: mudou o formato do
 * estado, apaga as linhas e reconstrói pelo log. Não é usado nos streams de reserva (2–4 eventos).
 */
@Component
public class EventSnapshots {

    private final ObjectMapper json;
    private final int every;
    private final EventStore eventStore;

    public EventSnapshots(ObjectMapper json, @Value("${events.snapshot.every:100}") int every, EventStore eventStore) {
        this.json = json;
        this.every = every;
        this.eventStore = eventStore;
    }

    /** O stream acabou de chegar em {@code version}: é hora de salvar um snapshot? */
    public boolean due(int version) {
        return version % every == 0;
    }

    /** Grava o snapshot (um por stream). Um snapshot mais antigo que o gravado é ignorado. */
    public void save(Connection conn, Snapshot s) throws SQLException {
        String sql = "INSERT INTO " + eventStore.tabela("event_snapshot") + " (stream_type, stream_id, version, state) "
                + "VALUES (?, ?, ?, ?::jsonb) ON CONFLICT (stream_type, stream_id) DO UPDATE "
                + "SET version = EXCLUDED.version, state = EXCLUDED.state, created_at = now() "
                + "WHERE event_snapshot.version < EXCLUDED.version";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, s.streamType());
            ps.setString(2, s.streamId());
            ps.setInt(3, s.version());
            ps.setString(4, s.state().toString());
            ps.executeUpdate();
        }
    }

    public Optional<Snapshot> load(Connection conn, String streamType, String streamId) throws SQLException {
        String sql = "SELECT version, state::text FROM " + eventStore.tabela("event_snapshot")
                + " WHERE stream_type = ? AND stream_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, streamType);
            ps.setString(2, streamId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Snapshot(streamType, streamId, rs.getInt(1), json.readTree(rs.getString(2))));
            }
        } catch (JsonProcessingException ex) {
            throw new SQLException("JSON inválido no event_snapshot", ex);
        }
    }
}
