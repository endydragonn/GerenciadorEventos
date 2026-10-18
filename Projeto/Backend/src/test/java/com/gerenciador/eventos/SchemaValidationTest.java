package com.gerenciador.eventos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.TestSupport.TestSchemas;
import com.gerenciador.eventos.shared.DatabaseConnection;

@SpringBootTest
@Import(GlobalDbTruncator.class)
public class SchemaValidationTest {

    @Autowired
    private DatabaseConnection databaseConnection;

    @Value(TestSchemas.PROPERTY)
    private String schemasConfig;

    @Test
    void shouldHaveRequiredTables() throws Exception {
        assertTrue(tableExists("users"), "Tabela 'users' não existe");
        assertTrue(tableExists("event"), "Tabela 'event' não existe");
        assertTrue(tableExists("eventcap"), "Tabela 'eventcap' não existe");
        assertTrue(tableExists("eventpresenter"), "Tabela 'eventpresenter' não existe");
        assertTrue(tableExists("mywallet"), "Tabela 'mywallet' não existe");
        assertTrue(tableExists("walletevent"), "Tabela 'walletevent' não existe");
        assertTrue(tableExists("event_availability"), "Tabela 'event_availability' não existe");
        assertTrue(tableExists("idempotency_key"), "Tabela 'idempotency_key' não existe");
        assertTrue(tableExists("event_store"), "Tabela 'event_store' não existe");
        assertTrue(tableExists("event_outbox"), "Tabela 'event_outbox' não existe");
        assertTrue(tableExists("event_snapshot"), "Tabela 'event_snapshot' não existe");
        assertTrue(tableExists("projection_checkpoint"), "Tabela 'projection_checkpoint' não existe");
    }

    @Test
    void usersTableShouldHaveKeyColumns() throws Exception {
        assertColumns("users",
            "user_id", "user_name", "email", "fone", "password", "birthdate",
            "admin", "isactive", "created_at", "updated_at"
        );
    }

    @Test
    void eventTableShouldHaveKeyColumns() throws Exception {
        assertColumns("event",
            "event_id", "creator_id", "event_name", "ead", "address",
            "event_date", "description", "created_at", "updated_at"
        );
    }

    @Test
    void eventCapTableShouldHaveKeyColumns() throws Exception {
        assertColumns("eventcap",
            "event_id", "capacity", "quant", "buy_time_limit", "max_time_in_line"
        );
    }

    @Test
    void eventPresenterTableShouldHaveKeyColumns() throws Exception {
        assertColumns("eventpresenter", "event_id", "presenter_name");
    }

    @Test
    void myWalletTableShouldHaveKeyColumns() throws Exception {
        assertColumns("mywallet",
            "user_id", "created_at", "updated_at"
        );
    }

    @Test
    void walletEventTableShouldHaveKeyColumns() throws Exception {
        assertColumns("walletevent",
            "user_id", "event_id", "status", "expires_at", "created_at", "updated_at"
        );
    }

    @Test
    void eventAvailabilityTableShouldHaveKeyColumns() throws Exception {
        assertColumns("event_availability",
            "event_id", "available", "refreshed_at", "created_at"
        );
    }

    @Test
    void idempotencyKeyTableShouldHaveKeyColumns() throws Exception {
        assertColumns("idempotency_key",
            "idempotency_key", "user_id", "event_id", "expires_at", "created_at"
        );
    }

    /** Issue #129: a chave de idempotência é escopada por usuário: PK = (user_id, idempotency_key), nessa ordem. */
    @Test
    void idempotencyKeyPrimaryKeyShouldBeUserIdAndKey() throws Exception {
        assertEquals(List.of("user_id", "idempotency_key"), constraintColumns("idempotency_key", 'p'), "PK de idempotency_key");
    }

    /** Issue #27: envelope do evento no event_store; a PK é a global_position e o UNIQUE dá a concorrência otimista. */
    @Test
    void eventStoreShouldHaveEnvelopeColumnsAndKeys() throws Exception {
        assertColumns("event_store",
            "global_position", "event_id", "stream_type", "stream_id", "version", "event_type",
            "schema_version", "occurred_at", "payload", "metadata", "partition_key"
        );
        assertEquals(List.of("global_position"), constraintColumns("event_store", 'p'), "PK de event_store");
        assertEquals(List.of("stream_type", "stream_id", "version"), constraintColumns("event_store", 'u'),
            "UNIQUE de event_store");
    }

    @Test
    void eventOutboxSnapshotAndCheckpointShouldHaveKeyColumnsAndKeys() throws Exception {
        assertColumns("event_outbox", "global_position");
        assertEquals(List.of("global_position"), constraintColumns("event_outbox", 'p'), "PK de event_outbox");
        assertColumns("event_snapshot", "stream_type", "stream_id", "version", "state");
        assertEquals(List.of("stream_type", "stream_id"), constraintColumns("event_snapshot", 'p'), "PK de event_snapshot");
        assertColumns("projection_checkpoint", "projecao", "stream_type", "stream_id", "version");
        assertEquals(List.of("projecao", "stream_type", "stream_id"), constraintColumns("projection_checkpoint", 'p'),
            "PK de projection_checkpoint");
    }

    /** Issue #27: INSERT+DELETE contínuo no event_outbox gera tuplas mortas; o autovacuum agressivo faz parte do schema. */
    @Test
    void eventOutboxShouldHaveAggressiveAutovacuum() throws Exception {
        String sql = "select array_to_string(c.reloptions, ',') from pg_class c "
            + "join pg_namespace n on n.oid = c.relnamespace where c.relname = 'event_outbox' and n.nspname = any(?)";
        String opcoes;
        try (Connection c = databaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("text", TestSchemas.parse(schemasConfig).toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Tabela 'event_outbox' não existe");
                opcoes = rs.getString(1);
            }
        }
        assertTrue(opcoes != null && opcoes.contains("autovacuum_vacuum_scale_factor=0.01"),
            "event_outbox sem autovacuum_vacuum_scale_factor baixo: " + opcoes);
    }

    /** Colunas, em ordem, da constraint do tipo {@code contype} ('p' = PK, 'u' = UNIQUE) da tabela. */
    private List<String> constraintColumns(String table, char contype) throws Exception {
        String sql = "select a.attname from pg_constraint c "
            + "join pg_class t on t.oid = c.conrelid "
            + "join pg_namespace n on n.oid = t.relnamespace "
            + "join unnest(c.conkey) with ordinality as k(attnum, ord) on true "
            + "join pg_attribute a on a.attrelid = t.oid and a.attnum = k.attnum "
            + "where c.contype = ?::\"char\" and t.relname = ? and n.nspname = any(?) order by k.ord";
        List<String> cols = new ArrayList<>();
        try (Connection c = databaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(contype));
            ps.setString(2, table);
            ps.setArray(3, c.createArrayOf("text", TestSchemas.parse(schemasConfig).toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) cols.add(rs.getString(1));
            }
        }
        return cols;
    }

    /** Issue #130: o job de retenção filtra por created_at; sem o índice cada rodada faz seq scan. */
    @Test
    void idempotencyKeyShouldHaveCreatedAtIndex() throws Exception {
        String sql = "select count(*) from pg_indexes where schemaname = any(?) "
            + "and tablename = 'idempotency_key' and indexname = 'idx_idempotency_key_created_at'";
        try (Connection c = databaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("text", TestSchemas.parse(schemasConfig).toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1), "índice idx_idempotency_key_created_at");
            }
        }
    }

    private boolean tableExists(String table) throws Exception {
        String sql = "select count(*) from information_schema.tables where table_schema = any(?) and table_name = ?";
        try (Connection c = databaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("text", TestSchemas.parse(schemasConfig).toArray()));
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private void assertColumns(String table, String... expected) throws Exception {
        String sql = "select column_name from information_schema.columns where table_schema = any(?) and table_name = ?";
        Set<String> expectedSet = new HashSet<>(Arrays.asList(expected));
        Set<String> actual = new HashSet<>();
        try (Connection c = databaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("text", TestSchemas.parse(schemasConfig).toArray()));
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    actual.add(rs.getString(1).toLowerCase());
                }
            }
        }
        assertTrue(actual.containsAll(lower(expectedSet)),
            () -> "Colunas faltando na tabela '" + table + "': " + diff(lower(expectedSet), actual));
    }

    private Set<String> lower(Set<String> in) {
        Set<String> out = new HashSet<>();
        for (String s : in) out.add(s.toLowerCase());
        return out;
    }

    private Set<String> diff(Set<String> expected, Set<String> actual) {
        Set<String> d = new HashSet<>(expected);
        d.removeAll(actual);
        return d;
    }
}
