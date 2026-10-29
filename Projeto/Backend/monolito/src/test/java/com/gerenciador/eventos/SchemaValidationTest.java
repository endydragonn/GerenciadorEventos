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
        assertTrue(tableExists("public.event_store"), "Tabela 'public.event_store' não existe");
        assertTrue(tableExists("public.event_outbox"), "Tabela 'public.event_outbox' não existe");
        assertTrue(tableExists("public.event_snapshot"), "Tabela 'public.event_snapshot' não existe");
        assertTrue(tableExists("public.projection_checkpoint"), "Tabela 'public.projection_checkpoint' não existe");
        assertTrue(tableExists("processed_event"), "Tabela 'notification.processed_event' não existe");
    }

    /** Fase 7 (#32): checkpoint do serviço notification, no schema próprio {@code notification}. */
    @Test
    void processedEventTableShouldHaveKeyColumns() throws Exception {
        assertColumns("processed_event", "event_id", "event_type", "processed_at");
        assertEquals(List.of("event_id"), constraintColumns("processed_event", 'p'), "PK de processed_event");
    }

    /** Issue #27: envelope do evento no event_store; a PK é a global_position e o UNIQUE dá a concorrência otimista. */
    @Test
    void eventStoreShouldHaveEnvelopeColumnsAndKeys() throws Exception {
        assertColumns("public.event_store",
            "global_position", "event_id", "stream_type", "stream_id", "version", "event_type",
            "schema_version", "occurred_at", "payload", "metadata", "partition_key"
        );
        assertEquals(List.of("global_position"), constraintColumns("public.event_store", 'p'), "PK de event_store");
        assertEquals(List.of("stream_type", "stream_id", "version"), constraintColumns("public.event_store", 'u'),
            "UNIQUE de event_store");
    }

    @Test
    void eventOutboxSnapshotAndCheckpointShouldHaveKeyColumnsAndKeys() throws Exception {
        assertColumns("public.event_outbox", "global_position");
        assertEquals(List.of("global_position"), constraintColumns("public.event_outbox", 'p'), "PK de event_outbox");
        assertColumns("public.event_snapshot", "stream_type", "stream_id", "version", "state");
        assertEquals(List.of("stream_type", "stream_id"), constraintColumns("public.event_snapshot", 'p'), "PK de event_snapshot");
        assertColumns("public.projection_checkpoint", "projecao", "stream_type", "stream_id", "version");
        assertEquals(List.of("projecao", "stream_type", "stream_id"), constraintColumns("public.projection_checkpoint", 'p'),
            "PK de projection_checkpoint");
    }

    /** Issue #27: INSERT+DELETE contínuo no event_outbox gera tuplas mortas; o autovacuum agressivo faz parte do schema. */
    @Test
    void eventOutboxShouldHaveAggressiveAutovacuum() throws Exception {
        String sql = "select array_to_string(c.reloptions, ',') from pg_class c "
            + "join pg_namespace n on n.oid = c.relnamespace where c.relname = 'event_outbox' and n.nspname = 'public'";
        String opcoes;
        try (Connection c = databaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
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
            ps.setString(2, nome(table));
            ps.setArray(3, c.createArrayOf("text", schemasDe(table)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) cols.add(rs.getString(1));
            }
        }
        return cols;
    }

    /**
     * Issue #33 (Fase 7): {@code eventcap}, {@code walletevent}, {@code event_availability} e {@code idempotency_key} SAÍRAM de
     * {@code public} para o schema {@code reservation} (do serviço reservation, validado no módulo dele), e não restou FK de
     * {@code public} para o schema novo: o monólito não lê nem referencia tabela do serviço.
     */
    @Test
    void reservationTablesLeftPublicAndNothingReferencesThem() throws Exception {
        for (String t : List.of("eventcap", "walletevent", "event_availability", "idempotency_key")) {
            assertTrue(!tableExists("public." + t), "public." + t + " deveria ter ido para o schema reservation");
            // desde a #47 o eventcap (capacidade) é do schema stock, as demais seguem em reservation
            String schema = "eventcap".equals(t) ? "stock" : "reservation";
            assertTrue(tableExists(schema + "." + t), schema + "." + t + " não existe");
        }
        String sql = "select count(*) from pg_constraint c join pg_class t on t.oid = c.conrelid "
            + "join pg_namespace n on n.oid = t.relnamespace join pg_class r on r.oid = c.confrelid "
            + "join pg_namespace rn on rn.oid = r.relnamespace "
            + "where c.contype = 'f' and n.nspname <> rn.nspname and (n.nspname in ('reservation', 'stock') or rn.nspname in ('reservation', 'stock'))";
        try (Connection c = databaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals(0, rs.getInt(1), "nenhuma FK cross-schema envolvendo reservation");
        }
    }

    /**
     * Issue #35 (Fase 7, cutover): {@code event} e {@code eventpresenter} SAÍRAM de {@code public} para o schema {@code catalog}
     * (do serviço catalog, validado no módulo dele: colunas, chaves, cascata), e não restou FK cruzando o schema {@code catalog}
     * (a {@code Event_creator_id_fkey} para {@code users} caiu).
     */
    @Test
    void catalogTablesLeftPublicAndNoFkCrossesTheCatalogSchema() throws Exception {
        for (String t : List.of("event", "eventpresenter")) {
            assertTrue(!tableExists("public." + t), "public." + t + " deveria ter ido para o schema catalog (migração 20261026)");
            assertTrue(tableExists("catalog." + t), "catalog." + t + " não existe");
        }
        assertEquals(0, contar("select count(*) from pg_constraint c join pg_class t on t.oid = c.conrelid "
            + "join pg_namespace n on n.oid = t.relnamespace join pg_class r on r.oid = c.confrelid "
            + "join pg_namespace rn on rn.oid = r.relnamespace "
            + "where c.contype = 'f' and n.nspname <> rn.nspname and 'catalog' in (n.nspname, rn.nspname)"),
            "nenhuma FK cross-schema envolvendo catalog");
    }

    /**
     * Issue #36 (Fase 7, cutover): {@code users}, {@code mywallet} e {@code pending_verifications} SAÍRAM de {@code public} para o
     * schema {@code identity} (do serviço identity, validado no módulo dele), com o trigger 1:1 da carteira e a
     * {@code mywallet_user_id_fkey} DENTRO do schema novo, e não restou FK cruzando o schema {@code identity}. O stream
     * {@code identity.notification} também saiu do event store de {@code public}.
     */
    @Test
    void identityTablesLeftPublicAndNoFkCrossesTheIdentitySchema() throws Exception {
        for (String t : List.of("users", "mywallet", "pending_verifications")) {
            assertTrue(!tableExists("public." + t), "public." + t + " deveria ter ido para o schema identity (migração 20261028)");
            assertTrue(tableExists("identity." + t), "identity." + t + " não existe");
        }
        assertEquals(0, contar("select count(*) from pg_constraint c join pg_class t on t.oid = c.conrelid "
            + "join pg_namespace n on n.oid = t.relnamespace join pg_class r on r.oid = c.confrelid "
            + "join pg_namespace rn on rn.oid = r.relnamespace "
            + "where c.contype = 'f' and n.nspname <> rn.nspname and 'identity' in (n.nspname, rn.nspname)"),
            "nenhuma FK cross-schema envolvendo identity");
        assertEquals(1, contar("select count(*) from pg_constraint where conname = 'mywallet_user_id_fkey' "
            + "and conrelid = 'identity.mywallet'::regclass and confrelid = 'identity.users'::regclass"),
            "a FK mywallet -> users mora dentro do schema identity");
        assertEquals(0, contar("select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
            + "where n.nspname = 'public' and p.proname in ('create_wallet_for_user', 'cleanup_expired_verifications', "
            + "'trigger_cleanup_on_insert')"), "as funções de usuário saíram de public");
    }

    /**
     * Asserção NEGATIVA (issue #59): capacidade, vagas e janela de compra saíram de {@code event} (#5) e de {@code public}
     * (#33/#35) e hoje são do {@code stock.eventcap} (desde a #47; antes, {@code reservation.eventcap}). {@code assertColumns} é "contém" e não pega coluna que volta;
     * nenhuma tabela de {@code public} pode tê-las.
     */
    @Test
    void publicSchemaHasNoCapacityNorBuyWindowColumns() throws Exception {
        assertEquals(0, contar("select count(*) from information_schema.columns where table_schema = 'public' "
            + "and column_name in ('capacity', 'quant', 'buy_time_limit', 'lot_quantity', 'max_time_in_line')"),
            "colunas de eventcap voltaram para public");
    }

    private int contar(String sql) throws Exception {
        try (Connection c = databaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** {@code "schema.tabela"} => só aquele schema; sem prefixo => os schemas configurados ({@code test.db.schemas}). */
    private Object[] schemasDe(String table) {
        return table.contains(".") ? new Object[] {table.substring(0, table.indexOf('.'))}
                : TestSchemas.parse(schemasConfig).toArray();
    }

    private static String nome(String table) {
        return table.substring(table.indexOf('.') + 1);
    }

    private boolean tableExists(String table) throws Exception {
        String sql = "select count(*) from information_schema.tables where table_schema = any(?) and table_name = ?";
        try (Connection c = databaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("text", schemasDe(table)));
            ps.setString(2, nome(table));
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
            ps.setArray(1, c.createArrayOf("text", schemasDe(table)));
            ps.setString(2, nome(table));
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
