package com.gerenciador.eventos;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.TestSupport.TestSchemas;
import com.gerenciador.eventos.shared.DatabaseConnection;

/** Harness de teste preparado para N schemas (issue #21): parse da config + descoberta de tabelas. */
@SpringBootTest
@Import(GlobalDbTruncator.class)
public class TestSchemasTest {

    private static final String SCHEMA_TMP = "tmp_schema_issue21";

    @Autowired
    private DatabaseConnection databaseConnection;

    @Test
    void parseAceitaListaComEspacosEIgnoraVazios() {
        assertEquals(List.of("public", "svc_a"), TestSchemas.parse(" public , svc_a ,"));
    }

    @Test
    void parseRejeitaNomeInvalidoEListaVazia() {
        assertThrows(IllegalArgumentException.class, () -> TestSchemas.parse("public;drop schema x"));
        assertThrows(IllegalArgumentException.class, () -> TestSchemas.parse("Public"));
        assertThrows(IllegalArgumentException.class, () -> TestSchemas.parse("1abc"));
        assertThrows(IllegalArgumentException.class, () -> TestSchemas.parse(" , "));
    }

    @Test
    void defaultCobreSoPublicEIncluiTabelasDoApp() throws Exception {
        try (Connection c = databaseConnection.getConnection()) {
            List<String> tabelas = TestSchemas.tabelas(c, TestSchemas.parse("public"));
            assertTrue(tabelas.containsAll(List.of("public.users", "public.mywallet", "public.event_store")));
            assertTrue(tabelas.stream().allMatch(t -> t.startsWith("public.")));
            assertFalse(tabelas.contains("public.eventcap"), "eventcap saiu de public na #33");
            assertFalse(tabelas.contains("public.event"), "event saiu de public na #35 (schema catalog)");
        }
    }

    /** Fase 7 (#32, #33, #35): os schemas dos serviços notification, reservation e catalog entram no harness (truncate + validação) por padrão. */
    @Test
    void defaultDoHarnessCobrePublicNotificationReservationECatalog() throws Exception {
        assertEquals(List.of("public", "notification", "reservation", "catalog"), TestSchemas.parse(TestSchemas.DEFAULT));
        try (Connection c = databaseConnection.getConnection()) {
            List<String> tabelas = TestSchemas.tabelas(c, TestSchemas.parse(TestSchemas.DEFAULT));
            assertTrue(tabelas.contains("notification.processed_event"), "tabelas: " + tabelas);
            assertTrue(tabelas.containsAll(List.of("reservation.eventcap", "reservation.walletevent",
                    "reservation.event_availability", "reservation.idempotency_key", "reservation.event_store")),
                    "tabelas: " + tabelas);
            assertTrue(tabelas.containsAll(List.of("catalog.event", "catalog.eventpresenter", "catalog.event_store")),
                    "tabelas: " + tabelas);
            assertTrue(tabelas.contains("public.users"));
        }
    }

    @Test
    void descobreETruncaTabelasDeSchemaExtra() throws Exception {
        try (Connection c = databaseConnection.getConnection(); Statement st = c.createStatement()) {
            try {
                st.execute("CREATE SCHEMA " + SCHEMA_TMP);
                st.execute("CREATE TABLE " + SCHEMA_TMP + ".t (id serial PRIMARY KEY, v text)");
                st.execute("INSERT INTO " + SCHEMA_TMP + ".t (v) VALUES ('x')");

                List<String> comTmp = TestSchemas.tabelas(c, TestSchemas.parse("public," + SCHEMA_TMP));
                assertTrue(comTmp.contains(SCHEMA_TMP + ".t"));
                assertTrue(comTmp.contains("public.users"));
                assertFalse(TestSchemas.tabelas(c, TestSchemas.parse("public")).contains(SCHEMA_TMP + ".t"));

                // truncate só do schema extra (não apaga dados de public no meio da suíte)
                assertEquals(1, contar(st, SCHEMA_TMP + ".t"));

                st.execute(TestSchemas.truncateSql(c, TestSchemas.parse(SCHEMA_TMP)));
                assertEquals(0, contar(st, SCHEMA_TMP + ".t"));
            } finally {
                st.execute("DROP SCHEMA IF EXISTS " + SCHEMA_TMP + " CASCADE");
            }
        }
    }

    @Test
    void schemaSemTabelasNaoGeraTruncate() throws Exception {
        try (Connection c = databaseConnection.getConnection()) {
            assertNull(TestSchemas.truncateSql(c, List.of("schema_que_nao_existe")));
        }
    }

    private int contar(Statement st, String tabela) throws Exception {
        try (var rs = st.executeQuery("SELECT count(*) FROM " + tabela)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
