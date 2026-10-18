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
        String sql = "select a.attname from pg_constraint c "
            + "join pg_class t on t.oid = c.conrelid "
            + "join pg_namespace n on n.oid = t.relnamespace "
            + "join unnest(c.conkey) with ordinality as k(attnum, ord) on true "
            + "join pg_attribute a on a.attrelid = t.oid and a.attnum = k.attnum "
            + "where c.contype = 'p' and t.relname = 'idempotency_key' and n.nspname = any(?) order by k.ord";
        List<String> pk = new ArrayList<>();
        try (Connection c = databaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("text", TestSchemas.parse(schemasConfig).toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) pk.add(rs.getString(1));
            }
        }
        assertEquals(List.of("user_id", "idempotency_key"), pk, "PK de idempotency_key");
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
