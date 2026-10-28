package com.gerenciador.eventos.catalog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
 * Valida o schema REAL do serviço catalog (issue #35, {@code db/init-scripts/14-catalog.sql}): tabelas, colunas, chaves, a
 * única FK (interna, {@code eventpresenter -> event} com {@code ON DELETE CASCADE}), nenhuma FK cross-schema e o event store
 * próprio. O schema do monólito ({@code public}) continua validado pelo {@code SchemaValidationTest} de lá.
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
public class SchemaValidationTest {

    @Autowired
    private DatabaseConnection databaseConnection;

    @Test
    void shouldHaveRequiredTables() throws Exception {
        for (String t : List.of("event", "eventpresenter", "event_store", "event_outbox", "event_snapshot",
                "projection_checkpoint")) {
            assertEquals(1, contar("select count(*) from information_schema.tables where table_schema = 'catalog' "
                    + "and table_name = ?", t), "Tabela 'catalog." + t + "' não existe");
        }
    }

    @Test
    void eventTableShouldHaveKeyColumns() throws Exception {
        assertEquals(List.of("event_id", "creator_id", "event_name", "ead", "address", "event_date", "description", "image_data",
                "created_at", "updated_at", "version"), colunas("event"));
        assertEquals(List.of("event_id"), constraintColumns("event", 'p'), "PK de event");
        assertEquals(List.of("event_name"), constraintColumns("event", 'u'), "UNIQUE de event_name");
        assertEquals("integer", tipo("event", "creator_id"), "creator_id segue integer (UUID é evolução)");
    }

    /**
     * Capacidade, vagas e janela de compra são do agregado {@code eventcap} (serviço reservation): saíram de {@code event}
     * (issue #5/#33). Asserção NEGATIVA explícita: a lista exata acima já pegaria, mas este teste diz o porquê e continua
     * valendo se alguém afrouxar aquela lista para "contém".
     */
    @Test
    void eventTableShouldNotCarryCapacityNorBuyWindow() throws Exception {
        assertNoColumns("event", "capacity", "quant", "buy_time_limit", "lot_quantity", "max_time_in_line");
        assertNoColumns("eventpresenter", "capacity", "quant", "buy_time_limit");
    }

    @Test
    void eventPresenterTableShouldHaveKeyColumns() throws Exception {
        assertEquals(List.of("event_id", "presenter_name"), colunas("eventpresenter"));
        assertEquals(List.of("event_id", "presenter_name"), constraintColumns("eventpresenter", 'p'), "PK de eventpresenter");
    }

    /** A única FK do schema é interna e em cascata; nenhuma FK de {@code catalog.*} aponta para outro schema (nem users). */
    @Test
    void soAFkInternaDeEventPresenterEmCascataENenhumaCrossSchema() throws Exception {
        assertEquals(1, contar("select count(*) from pg_constraint where conname = 'eventpresenter_event_id_fkey' "
                + "and conrelid = 'catalog.eventpresenter'::regclass and confrelid = 'catalog.event'::regclass "
                + "and confdeltype = 'c'"), "eventpresenter_event_id_fkey com ON DELETE CASCADE");
        assertEquals(0, contar("select count(*) from pg_constraint c join pg_class t on t.oid = c.conrelid "
                + "join pg_class r on r.oid = c.confrelid where c.contype = 'f' "
                + "and t.relnamespace = 'catalog'::regnamespace and r.relnamespace <> 'catalog'::regnamespace"),
                "zero FKs de catalog.* para outro schema");
        assertEquals(List.of(), constraintColumns("event", 'f'), "event.creator_id sem FK (referência solta)");
    }

    @Test
    void creatorIdTemIndice() throws Exception {
        assertEquals(1, contar("select count(*) from pg_indexes where schemaname = 'catalog' and tablename = 'event' "
                + "and indexname = 'idx_event_creator_id'"));
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
                     + "where oid = 'catalog.event_outbox'::regclass");
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
        return lista("select column_name from information_schema.columns where table_schema = 'catalog' and table_name = ? "
                + "order by ordinal_position", tabela);
    }

    /** Nenhuma das {@code proibidas} existe em {@code catalog.<tabela>} (uma coluna que voltou não passa despercebida). */
    private void assertNoColumns(String tabela, String... proibidas) throws Exception {
        List<String> presentes = new ArrayList<>(colunas(tabela));
        presentes.retainAll(List.of(proibidas));
        assertEquals(List.of(), presentes, "catalog." + tabela + " não deve ter estas colunas");
    }

    private String tipo(String tabela, String coluna) throws Exception {
        List<String> t = lista("select data_type from information_schema.columns where table_schema = 'catalog' "
                + "and table_name = ? and column_name = ?", tabela, coluna);
        return t.isEmpty() ? null : t.get(0);
    }

    /** Colunas, em ordem, da constraint do tipo {@code contype} ('p' = PK, 'u' = UNIQUE, 'f' = FK) da tabela. */
    private List<String> constraintColumns(String tabela, char contype) throws Exception {
        return lista("select a.attname from pg_constraint c "
                + "join unnest(c.conkey) with ordinality as k(attnum, ord) on true "
                + "join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.attnum "
                + "where c.contype = ?::\"char\" and c.conrelid = ('catalog.' || ?)::regclass order by k.ord",
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
