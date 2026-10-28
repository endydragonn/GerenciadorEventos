package com.gerenciador.eventos.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Valida o schema REAL do serviço identity (issue #36, {@code db/init-scripts/15-identity.sql}): tabelas, colunas, chaves, a única FK
 * (interna, {@code mywallet -> users} com {@code ON DELETE CASCADE}), nenhuma FK cross-schema, o trigger que cria a carteira NO schema
 * identity e o event store próprio. O schema do monólito ({@code public}) continua validado pelo {@code SchemaValidationTest} de lá.
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
public class SchemaValidationTest {

    @Autowired
    private DatabaseConnection databaseConnection;

    @Test
    void shouldHaveRequiredTables() throws Exception {
        for (String t : List.of("users", "mywallet", "pending_verifications", "event_store", "event_outbox", "event_snapshot",
                "projection_checkpoint")) {
            assertEquals(1, contar("select count(*) from information_schema.tables where table_schema = 'identity' "
                    + "and table_name = ?", t), "Tabela 'identity." + t + "' não existe");
        }
    }

    @Test
    void usersTableShouldHaveKeyColumnsAndUniqueKeys() throws Exception {
        assertEquals(List.of("user_id", "user_name", "email", "fone", "password", "birthdate", "admin", "host", "isactive",
                "created_at", "updated_at", "password_reset_mfa_code", "password_reset_mfa_expiration"), colunas("users"));
        assertEquals(List.of("user_id"), constraintColumns("users", 'p'), "PK de users");
        assertEquals(List.of("email", "fone", "user_name"), constraintColumns("users", 'u').stream().sorted().toList(),
                "UNIQUE de email, fone e user_name");
        assertEquals("integer", tipo("users", "user_id"), "user_id segue integer (UUID é evolução)");
    }

    @Test
    void pendingVerificationsShouldHaveKeyColumns() throws Exception {
        assertEquals(List.of("id", "email", "fone", "user_name", "password_hash", "birthdate", "verification_code", "expires_at",
                "created_at", "attempts", "max_attempts"), colunas("pending_verifications"));
        assertEquals(List.of("email"), constraintColumns("pending_verifications", 'u'), "UNIQUE de email");
    }

    /** A única FK do schema é interna e em cascata; nenhuma FK de {@code identity.*} aponta para outro schema, nem o contrário. */
    @Test
    void soAFkInternaDeMyWalletEmCascataENenhumaCrossSchema() throws Exception {
        assertEquals(1, contar("select count(*) from pg_constraint where conname = 'mywallet_user_id_fkey' "
                + "and conrelid = 'identity.mywallet'::regclass and confrelid = 'identity.users'::regclass "
                + "and confdeltype = 'c'"), "mywallet_user_id_fkey com ON DELETE CASCADE, em identity");
        assertEquals(0, contar("select count(*) from pg_constraint c join pg_class t on t.oid = c.conrelid "
                + "join pg_class r on r.oid = c.confrelid where c.contype = 'f' "
                + "and (t.relnamespace = 'identity'::regnamespace) <> (r.relnamespace = 'identity'::regnamespace)"),
                "zero FKs de identity.* para fora e de fora para identity.*");
    }

    /** O trigger 1:1 vive em identity.users e cria a carteira em identity.mywallet (função com "identity." fixo). */
    @Test
    void triggerCriaACarteiraNoSchemaIdentity() throws Exception {
        assertEquals(1, contar("select count(*) from pg_trigger where tgname = 'trigger_create_wallet' "
                + "and tgrelid = 'identity.users'::regclass and tgfoid = 'identity.create_wallet_for_user()'::regprocedure"));
        try (Connection c = databaseConnection.getConnection(); Statement st = c.createStatement()) {
            st.execute("insert into identity.users (user_name, email, fone, password, birthdate) "
                    + "values ('trigger-schema', 'trigger-schema@test.com', '5599000000', 'pw', '1990-01-01')");
            assertEquals(1, contar("select count(*) from identity.mywallet w join identity.users u using (user_id) "
                    + "where u.email = 'trigger-schema@test.com'"), "carteira criada no mesmo INSERT");
            st.execute("delete from identity.users where email = 'trigger-schema@test.com'");
            assertEquals(0, contar("select count(*) from identity.mywallet w left join identity.users u using (user_id) "
                    + "where u.user_id is null"), "a cascata apaga a carteira junto");
        }
    }

    @Test
    void limpezaDeVerificacoesVencidasApontaParaOSchemaIdentity() throws Exception {
        assertEquals(1, contar("select count(*) from pg_trigger where tgname = 'trigger_cleanup_on_create_verification' "
                + "and tgrelid = 'identity.pending_verifications'::regclass"));
        assertEquals(3, contar("select count(*) from pg_proc where pronamespace = 'identity'::regnamespace "
                + "and proname in ('cleanup_expired_verifications', 'trigger_cleanup_on_insert', 'create_wallet_for_user')"));
    }

    @Test
    void eventStoreShouldHaveEnvelopeColumnsAndKeys() throws Exception {
        assertEquals(List.of("global_position", "event_id", "stream_type", "stream_id", "version", "event_type", "schema_version",
                "occurred_at", "payload", "metadata", "partition_key"), colunas("event_store"));
        assertEquals(List.of("global_position"), constraintColumns("event_store", 'p'), "PK de event_store");
        assertEquals(List.of("stream_type", "stream_id", "version"), constraintColumns("event_store", 'u'), "UNIQUE de event_store");
        assertEquals(List.of("global_position"), constraintColumns("event_outbox", 'p'), "PK de event_outbox");
        assertEquals(List.of("stream_type", "stream_id"), constraintColumns("event_snapshot", 'p'), "PK de event_snapshot");
        assertEquals(List.of("projecao", "stream_type", "stream_id"), constraintColumns("projection_checkpoint", 'p'),
                "PK de projection_checkpoint");
    }

    @Test
    void eventOutboxShouldHaveAggressiveAutovacuum() throws Exception {
        try (Connection c = databaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement("select array_to_string(reloptions, ',') from pg_class "
                     + "where oid = 'identity.event_outbox'::regclass");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            String opcoes = rs.getString(1);
            assertTrue(opcoes != null && opcoes.contains("autovacuum_vacuum_scale_factor=0.01"), "event_outbox: " + opcoes);
        }
    }

    // ===================== helpers =====================

    private long contar(String sql, String... params) throws Exception {
        try (Connection c = databaseConnection.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private List<String> colunas(String tabela) throws Exception {
        return lista("select column_name from information_schema.columns where table_schema = 'identity' and table_name = ? "
                + "order by ordinal_position", tabela);
    }

    private String tipo(String tabela, String coluna) throws Exception {
        List<String> t = lista("select data_type from information_schema.columns where table_schema = 'identity' "
                + "and table_name = ? and column_name = ?", tabela, coluna);
        return t.isEmpty() ? null : t.get(0);
    }

    /** Colunas, em ordem, da constraint do tipo {@code contype} ('p' = PK, 'u' = UNIQUE, 'f' = FK) da tabela. */
    private List<String> constraintColumns(String tabela, char contype) throws Exception {
        return lista("select a.attname from pg_constraint c "
                + "join unnest(c.conkey) with ordinality as k(attnum, ord) on true "
                + "join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.attnum "
                + "where c.contype = ?::\"char\" and c.conrelid = ('identity.' || ?)::regclass order by k.ord",
                String.valueOf(contype), tabela);
    }

    private List<String> lista(String sql, String... params) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = databaseConnection.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }
}
