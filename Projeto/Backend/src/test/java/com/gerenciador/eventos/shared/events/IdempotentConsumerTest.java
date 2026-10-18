package com.gerenciador.eventos.shared.events;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * Base do consumer idempotente (issue #27): checkpoint atômico por stream, conferência do {@code eventId} contra o
 * {@code event_store}, regra do buraco. Chama {@code process} direto (sem broker): a camada Kafka é só entrega.
 */
@SpringBootTest(properties = "events.consumer.enabled=true")
@Import(GlobalDbTruncator.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IdempotentConsumerTest {

    private static final String TIPO = "t27.consumer";

    @Autowired private IdempotentConsumer consumer;
    @Autowired private EventStore eventStore;
    @Autowired private DatabaseConnection db;
    @Autowired private ObjectMapper json;

    private final Projection proj = new TestSomatorio();

    @BeforeEach
    void preparar() throws Exception {
        TestSomatorio.criarTabela(db);
        EventsTestSupport.limpar(db);
    }

    /** Appenda a version no event_store (o "comandante") e devolve o envelope, como o broker o entregaria. */
    private EventEnvelope append(String stream, int version, int valor) throws Exception {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            EventEnvelope e = eventStore.append(c, NovoEvento.of(TIPO, stream, version, "Teste",
                    json.createObjectNode().put("valor", valor), "k")).orElseThrow();
            c.commit();
            return e;
        }
    }

    private int checkpoint(String stream) throws Exception {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT version FROM public.projection_checkpoint "
                     + "WHERE projecao = ? AND stream_type = ? AND stream_id = ?")) {
            ps.setString(1, proj.name());
            ps.setString(2, TIPO);
            ps.setString(3, stream);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private long[] estado(String stream) throws Exception {
        return TestSomatorio.ler(db, "public", stream);
    }

    @AfterAll
    void derrubarTabelaDeTeste() throws Exception {
        TestSomatorio.derrubarTabela(db);
    }

    @Test
    void aplicaEmOrdemEAvancaOCheckpoint() throws Exception {
        String s = "s-" + UUID.randomUUID();
        assertTrue(consumer.process(proj, append(s, 1, 10)));
        assertTrue(consumer.process(proj, append(s, 2, 20)));
        assertEquals(2, checkpoint(s));
        assertArrayEquals(new long[] {2, 30}, estado(s));
    }

    @Test
    void reenvioDaMesmaMensagemEDescartado() throws Exception {
        String s = "s-" + UUID.randomUUID();
        EventEnvelope v1 = append(s, 1, 10);
        assertTrue(consumer.process(proj, v1));
        assertFalse(consumer.process(proj, v1), "version <= checkpoint: descarta");
        assertArrayEquals(new long[] {1, 10}, estado(s), "não contou duas vezes");
    }

    @Test
    void eventIdDivergenteDoEventStoreEDescartadoSemAvancar() throws Exception {
        String s = "s-" + UUID.randomUUID();
        EventEnvelope real = append(s, 1, 10);
        EventEnvelope forjado = new EventEnvelope(UUID.randomUUID(), real.streamType(), real.streamId(), 1,
                real.globalPosition(), real.eventType(), 1, real.occurredAt(), real.payload(), real.metadata());

        assertFalse(consumer.process(proj, forjado), "outro eventId na mesma (stream, version): sobra de outro teste");
        assertEquals(0, checkpoint(s));
        assertNull(estado(s));
    }

    @Test
    void mensagemDeEventoQueNaoExisteNoEventStoreEDescartada() throws Exception {
        String s = "s-" + UUID.randomUUID();
        EventEnvelope real = append(s, 1, 10);
        EventsTestSupport.limpar(db); // o TRUNCATE ... RESTART IDENTITY de um teste anterior
        assertFalse(consumer.process(proj, real));
        assertEquals(0, checkpoint(s));
    }

    @Test
    void buracoEPreenchidoRelendoOEventStoreEmOrdem() throws Exception {
        String s = "s-" + UUID.randomUUID();
        append(s, 1, 1);
        append(s, 2, 2);
        EventEnvelope v3 = append(s, 3, 4);

        assertTrue(consumer.process(proj, v3), "chegou a v3 sem ninguém ter processado a v1 e a v2");

        assertEquals(3, checkpoint(s));
        assertArrayEquals(new long[] {3, 7}, estado(s));
    }

    @Test
    void buracoQueFaltaMesmoNoEventStoreLancaENaoAvanca() throws Exception {
        String s = "s-" + UUID.randomUUID();
        append(s, 1, 1);
        EventEnvelope v3 = append(s, 3, 4); // a v2 nunca foi gravada

        assertThrows(IllegalStateException.class, () -> consumer.process(proj, v3));

        assertEquals(0, checkpoint(s), "nada avança (nem a v1): retry/DLT decide");
        assertNull(estado(s));
    }

    @Test
    void falhaNaProjecaoDesfazOAvancoDoCheckpoint() throws Exception {
        String s = "s-" + UUID.randomUUID();
        assertTrue(consumer.process(proj, append(s, 1, 10)));
        EventEnvelope v2 = append(s, 2, 20);
        Projection quebrada = new TestSomatorio() {
            @Override
            public void apply(Connection conn, EventEnvelope e, String schema) {
                throw new IllegalArgumentException("projeção falhou");
            }
        };

        assertThrows(IllegalArgumentException.class, () -> consumer.process(quebrada, v2));

        assertEquals(1, checkpoint(s), "checkpoint e projeção na mesma transação");
        assertTrue(consumer.process(proj, v2), "o retry aplica");
        assertArrayEquals(new long[] {2, 30}, estado(s));
    }

    @Test
    void aplicadoresConcorrentesNuncaContamOMesmoEventoDuasVezes() throws Exception {
        String s = "s-" + UUID.randomUUID();
        List<EventEnvelope> msgs = new ArrayList<>();
        for (int v = 1; v <= 5; v++) {
            msgs.add(append(s, v, v));
        }
        int aplicadores = 8;
        CyclicBarrier largada = new CyclicBarrier(aplicadores);
        ExecutorService pool = Executors.newFixedThreadPool(aplicadores);
        try {
            List<Future<Integer>> rs = new ArrayList<>();
            for (int i = 0; i < aplicadores; i++) {
                rs.add(pool.submit(() -> {
                    largada.await();
                    int aplicados = 0;
                    for (EventEnvelope m : msgs) {
                        aplicados += consumer.process(proj, m) ? 1 : 0;
                    }
                    return aplicados;
                }));
            }
            int total = 0;
            for (Future<Integer> f : rs) {
                total += f.get();
            }
            assertEquals(5, total, "cada versão aplicada por exatamente um aplicador");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(5, checkpoint(s));
        assertArrayEquals(new long[] {5, 15}, estado(s));
    }
}
