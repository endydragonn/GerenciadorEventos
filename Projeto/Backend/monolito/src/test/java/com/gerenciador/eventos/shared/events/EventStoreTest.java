package com.gerenciador.eventos.shared.events;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.TestSupport.EventsTestSupport;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;

/** Append com concorrência otimista, outbox na transação do chamador e leitura de stream (issue #27). */
@SpringBootTest
@Import(GlobalDbTruncator.class)
public class EventStoreTest {

    @Autowired private EventStore eventStore;
    @Autowired private DatabaseConnection db;
    @Autowired private ObjectMapper json;

    @BeforeEach
    void limpar() throws Exception {
        EventsTestSupport.limpar(db);
    }

    private NovoEvento evento(String stream, int version) {
        return NovoEvento.of("t27.store", stream, version, "Teste", json.createObjectNode().put("v", version), "42");
    }

    private Optional<EventEnvelope> appendComitando(NovoEvento e) throws Exception {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            Optional<EventEnvelope> r = eventStore.append(c, e);
            c.commit();
            return r;
        }
    }

    private long contar(String tabela) throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from public." + tabela)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void appendGravaOEventoComEnvelopeCompletoENoOutbox() throws Exception {
        EventEnvelope env = appendComitando(evento("a", 1)).orElseThrow();

        assertEquals("t27.store", env.streamType());
        assertEquals(1, env.version());
        assertEquals(1, env.schemaVersion());
        assertTrue(env.globalPosition() > 0, "global_position alocada pelo banco");
        assertNotNull(env.occurredAt(), "occurred_at vem do banco (clock_timestamp)");
        assertEquals(1, contar("event_store"));
        assertEquals(1, contar("event_outbox"));
    }

    @Test
    void versaoJaGravadaDevolveConflitoSemExcecaoENemOutbox() throws Exception {
        assertTrue(appendComitando(evento("a", 1)).isPresent());
        // sem unique_violation: a mesma Connection segue utilizável depois do conflito
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            assertTrue(eventStore.append(c, evento("a", 1)).isEmpty(), "mesma version = conflito");
            assertTrue(eventStore.append(c, evento("a", 2)).isPresent(), "transação não foi abortada pelo conflito");
            c.commit();
        }
        assertEquals(2, contar("event_store"));
        assertEquals(2, contar("event_outbox"));
    }

    @Test
    void rollbackDoChamadorNaoDeixaEventoNemOutbox() throws Exception {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            assertTrue(eventStore.append(c, evento("a", 1)).isPresent());
            c.rollback();
        }
        assertEquals(0, contar("event_store"));
        assertEquals(0, contar("event_outbox"));
    }

    @Test
    void duasTransacoesNaMesmaVersaoSoUmaVence() throws Exception {
        CyclicBarrier largada = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Optional<EventEnvelope>>> rs = List.of(
                    pool.submit(() -> { largada.await(); return appendComitando(evento("a", 1)); }),
                    pool.submit(() -> { largada.await(); return appendComitando(evento("a", 1)); }));
            long vencedores = 0;
            for (Future<Optional<EventEnvelope>> f : rs) {
                vencedores += f.get().isPresent() ? 1 : 0;
            }
            assertEquals(1, vencedores);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, contar("event_store"));
        assertEquals(1, contar("event_outbox"));
    }

    @Test
    void readStreamDevolveEmOrdemDeVersionAPartirDeUmaVersion() throws Exception {
        String stream = "s-" + UUID.randomUUID();
        for (int v = 1; v <= 4; v++) {
            appendComitando(evento(stream, v));
        }
        appendComitando(evento("outro", 1));

        try (Connection c = db.getConnection()) {
            List<EventEnvelope> todos = eventStore.readStream(c, "t27.store", stream, 1);
            List<EventEnvelope> cauda = eventStore.readStream(c, "t27.store", stream, 3);
            assertEquals(List.of(1, 2, 3, 4), todos.stream().map(EventEnvelope::version).toList());
            assertEquals(List.of(3, 4), cauda.stream().map(EventEnvelope::version).toList());
            assertEquals(3, cauda.get(0).payload().get("v").asInt());
        }
    }
}
