package com.gerenciador.eventos.shared.events;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.TestSupport.EventsTestSupport;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Teste de replay (aceite da #27): append de N eventos, a projeção viva sai do consumer, a sombra é reconstruída do
 * zero a partir do {@code event_store} e as duas têm de ser IDÊNTICAS. Mais: snapshot + cauda == replay completo.
 */
@SpringBootTest(properties = "events.consumer.enabled=true")
@Import(GlobalDbTruncator.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class EventReplayTest {

    private static final String TIPO = "t27.replay";
    private static final String SOMBRA = "replay_t27";

    @Autowired private IdempotentConsumer consumer;
    @Autowired private EventStore eventStore;
    @Autowired private EventReplay replay;
    @Autowired private EventSnapshots snapshots;
    @Autowired private DatabaseConnection db;
    @Autowired private ObjectMapper json;

    private final Projection proj = new TestSomatorio();

    @BeforeEach
    void preparar() throws Exception {
        TestSomatorio.criarTabela(db);
        EventsTestSupport.limpar(db);
    }

    @AfterEach
    void derrubarSombra() throws Exception {
        replay.drop(SOMBRA);
    }

    private EventEnvelope append(String stream, int version, int valor) throws Exception {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            EventEnvelope e = eventStore.append(c, NovoEvento.of(TIPO, stream, version, "Teste",
                    json.createObjectNode().put("valor", valor), "k")).orElseThrow();
            c.commit();
            return e;
        }
    }

    private boolean schemaExiste(String nome) throws Exception {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("select 1 from pg_namespace where nspname = ?")) {
            ps.setString(1, nome);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @AfterAll
    void derrubarTabelaDeTeste() throws Exception {
        TestSomatorio.derrubarTabela(db);
    }

    @Test
    void reconstruirDoZeroNaSombraDaOMesmoResultadoDaProjecaoViva() throws Exception {
        Random rnd = new Random(27);
        int[] versao = new int[5];
        for (int i = 0; i < 60; i++) {             // 60 eventos intercalados em 5 streams
            int s = rnd.nextInt(5);
            EventEnvelope e = append("s" + s, ++versao[s], rnd.nextInt(100));
            consumer.process(proj, e);
        }

        replay.rebuild(proj, TIPO, SOMBRA);

        assertEquals(List.of(), replay.divergentTables(proj, SOMBRA), "viva e sombra idênticas");
        for (int s = 0; s < 5; s++) {
            assertArrayEquals(TestSomatorio.ler(db, "public", "s" + s), TestSomatorio.ler(db, SOMBRA, "s" + s));
            assertEquals(versao[s], TestSomatorio.ler(db, SOMBRA, "s" + s)[0], "todos os eventos do stream");
        }
    }

    @Test
    void sombraDivergenteDaVivaEDetectada() throws Exception {
        consumer.process(proj, append("s0", 1, 10));
        replay.rebuild(proj, TIPO, SOMBRA);
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE public.test_somatorio SET soma = soma + 1");
        }
        assertEquals(List.of("test_somatorio"), replay.divergentTables(proj, SOMBRA));
    }

    @Test
    void sombraEmSchemaProprioQueOReplayCriaEDerruba() throws Exception {
        consumer.process(proj, append("s0", 1, 10));
        replay.rebuild(proj, TIPO, SOMBRA);
        assertTrue(schemaExiste(SOMBRA));
        replay.drop(SOMBRA);
        assertTrue(!schemaExiste(SOMBRA));
        assertThrows(IllegalArgumentException.class, () -> replay.rebuild(proj, TIPO, "public"));
    }

    @Test
    void snapshotMaisCaudaDaOMesmoEstadoDoReplayCompleto() throws Exception {
        String stream = "grande";
        for (int v = 1; v <= 250; v++) {
            append(stream, v, v);
        }
        try (Connection c = db.getConnection()) {
            // estado do agregado = soma dos valores; snapshot na v100 (política: a cada 100)
            long somaAte100 = soma(eventStore.readStream(c, TIPO, stream, 1).subList(0, 100));
            assertTrue(snapshots.due(100) && snapshots.due(200) && !snapshots.due(150));
            snapshots.save(c, new Snapshot(TIPO, stream, 100, json.createObjectNode().put("soma", somaAte100)));
            snapshots.save(c, new Snapshot(TIPO, stream, 50, json.createObjectNode().put("soma", -1)));

            Snapshot snap = snapshots.load(c, TIPO, stream).orElseThrow();
            assertEquals(100, snap.version(), "snapshot mais antigo não sobrescreve o mais novo");
            long viaSnapshot = snap.state().get("soma").asLong()
                    + soma(eventStore.readStream(c, TIPO, stream, snap.version() + 1));
            long viaReplayCompleto = soma(eventStore.readStream(c, TIPO, stream, 1));

            assertEquals(viaReplayCompleto, viaSnapshot);
            assertEquals(250L * 251 / 2, viaReplayCompleto);
            assertEquals(150, eventStore.readStream(c, TIPO, stream, snap.version() + 1).size(), "só a cauda é lida");
        }
    }

    @Test
    void paginasDoReplayLeemOMesmoSnapshotMesmoComEventoComitadoNoMeio() throws Exception {
        for (int i = 1; i <= 5; i++) {
            append("s" + i, 1, i);
        }
        // página de 2: 3 páginas; durante a 1ª o teste comita um evento NOVO (global_position maior que todos)
        EventReplay paginado = new EventReplay(db, eventStore, 2);
        AtomicBoolean comitou = new AtomicBoolean();
        Projection intrometida = new TestSomatorio() {
            @Override
            public void apply(Connection conn, EventEnvelope e, String schema) throws SQLException {
                super.apply(conn, e, schema);
                if (comitou.compareAndSet(false, true)) {
                    try {
                        append("novo", 1, 100);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                }
            }
        };

        paginado.rebuild(intrometida, TIPO, SOMBRA);

        assertTrue(comitou.get());
        assertNull(TestSomatorio.ler(db, SOMBRA, "novo"), "REPEATABLE READ: o que comitou no meio fica fora desta sombra");
        for (int i = 1; i <= 5; i++) {
            assertArrayEquals(new long[] {1, i}, TestSomatorio.ler(db, SOMBRA, "s" + i));
        }
        replay.rebuild(proj, TIPO, SOMBRA);
        assertArrayEquals(new long[] {1, 100}, TestSomatorio.ler(db, SOMBRA, "novo"), "o próximo replay o enxerga");
    }

    private static long soma(List<EventEnvelope> eventos) {
        return eventos.stream().mapToLong(e -> e.payload().get("valor").asLong()).sum();
    }
}
