package com.gerenciador.eventos.shared.events;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.support.Acknowledgment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.TestSupport.EventsTestSupport;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Base do consumer idempotente (issue #27): checkpoint atômico por stream, conferência do {@code eventId} contra o
 * {@code event_store}, regra do buraco. Chama {@code process}/{@code onBatch} direto (sem broker): a camada Kafka é só
 * entrega. Os testes de {@code onBatch} são da #146 (lote numa transação, ack só depois do commit, falha no meio do lote).
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

    // ===================== em lote (#146) =====================

    private final AtomicInteger acks = new AtomicInteger();
    private final Acknowledgment ack = acks::incrementAndGet;

    @BeforeEach
    void zerarAcks() {
        acks.set(0); // o ciclo de vida é PER_CLASS: o contador é compartilhado entre os testes
    }

    private List<String> lote(EventEnvelope... msgs) throws Exception {
        List<String> r = new ArrayList<>();
        for (EventEnvelope m : msgs) {
            r.add(json.writeValueAsString(m));
        }
        return r;
    }

    private static long aplicadas(List<Optional<EventEnvelope>> resultado) {
        return resultado.stream().filter(Optional::isPresent).count();
    }

    @Test
    void loteAplicaEmOrdemComUmAckEDescartaDuplicataDentroDoLote() throws Exception {
        String s = "s-" + UUID.randomUUID();
        EventEnvelope v1 = append(s, 1, 10);
        EventEnvelope v2 = append(s, 2, 20);

        List<Optional<EventEnvelope>> r = consumer.onBatch(proj, lote(v1, v2, v1), ack);

        assertEquals(List.of(true, true, false), r.stream().map(Optional::isPresent).toList(), "a repetição é descartada");
        assertEquals(2, checkpoint(s));
        assertArrayEquals(new long[] {2, 30}, estado(s), "v1 contada uma vez só");
        assertEquals(1, acks.get(), "um ack por lote, depois do commit");
    }

    @Test
    void loteJaAplicadoInteiroEDescartadoSemContarDeNovoMasDaAck() throws Exception {
        String s = "s-" + UUID.randomUUID();
        EventEnvelope v1 = append(s, 1, 10);
        EventEnvelope v2 = append(s, 2, 20);
        consumer.onBatch(proj, lote(v1, v2), ack);

        List<Optional<EventEnvelope>> r = consumer.onBatch(proj, lote(v1, v2), ack);

        assertEquals(0, aplicadas(r), "reentrega do lote inteiro (rebalance/seek): tudo descartado");
        assertArrayEquals(new long[] {2, 30}, estado(s));
        assertEquals(2, acks.get(), "o ack acontece mesmo assim: senão o offset nunca avança");
    }

    @Test
    void loteComDoisStreamsIntercaladosMantemAOrdemDeCadaStream() throws Exception {
        String a = "a-" + UUID.randomUUID();
        String b = "b-" + UUID.randomUUID();
        EventEnvelope a1 = append(a, 1, 1);
        EventEnvelope b1 = append(b, 1, 100);
        EventEnvelope a2 = append(a, 2, 2);
        EventEnvelope b2 = append(b, 2, 200);

        List<Optional<EventEnvelope>> r = consumer.onBatch(proj, lote(a1, b1, a2, b2, b2), ack);

        assertEquals(4, aplicadas(r));
        assertEquals(2, checkpoint(a));
        assertEquals(2, checkpoint(b));
        assertArrayEquals(new long[] {2, 3}, estado(a));
        assertArrayEquals(new long[] {2, 300}, estado(b));
    }

    @Test
    void loteComBuracoPreencheDoEventStoreEmOrdem() throws Exception {
        String s = "s-" + UUID.randomUUID();
        append(s, 1, 1);
        append(s, 2, 2);
        EventEnvelope v3 = append(s, 3, 4);
        EventEnvelope outroV1 = append("o-" + UUID.randomUUID(), 1, 9);

        List<Optional<EventEnvelope>> r = consumer.onBatch(proj, lote(v3, outroV1), ack);

        assertEquals(2, aplicadas(r));
        assertEquals(3, checkpoint(s), "a v3 chegou sem ninguém ter processado a v1 e a v2");
        assertArrayEquals(new long[] {3, 7}, estado(s));
    }

    @Test
    void loteComEventIdDivergenteDescartaSoEssaMensagem() throws Exception {
        String a = "a-" + UUID.randomUUID();
        String b = "b-" + UUID.randomUUID();
        EventEnvelope a1 = append(a, 1, 1);
        EventEnvelope b1 = append(b, 1, 5);
        EventEnvelope forjado = new EventEnvelope(UUID.randomUUID(), b1.streamType(), b1.streamId(), 1,
                b1.globalPosition(), b1.eventType(), 1, b1.occurredAt(), b1.payload(), b1.metadata());
        EventEnvelope a2 = append(a, 2, 2);

        List<Optional<EventEnvelope>> r = consumer.onBatch(proj, lote(a1, forjado, a2), ack);

        assertEquals(List.of(true, false, true), r.stream().map(Optional::isPresent).toList());
        assertEquals(0, checkpoint(b), "outro eventId na mesma (stream, version): sobra de outro teste");
        assertNull(estado(b));
        assertArrayEquals(new long[] {2, 3}, estado(a), "o resto do lote segue");
    }

    /**
     * O invariante da falha no meio do lote: o error handler comita o offset de 0..i-1, então o banco já tem que ter
     * comitado essas mensagens (um evento perdido aqui nunca seria preenchido como "buraco"). O ack NÃO é dado.
     */
    @Test
    void buracoNoMeioDoLoteComitaOPrefixoEAcusaSoAMensagemCulpada() throws Exception {
        String s1 = "s1-" + UUID.randomUUID();
        String s2 = "s2-" + UUID.randomUUID();
        EventEnvelope s1v1 = append(s1, 1, 1);
        append(s2, 1, 1);
        EventEnvelope s2v3 = append(s2, 3, 3); // a v2 nunca foi gravada
        EventEnvelope s1v2 = append(s1, 2, 2);

        BatchListenerFailedException ex = assertThrows(BatchListenerFailedException.class,
                () -> consumer.onBatch(proj, lote(s1v1, s2v3, s1v2), ack));

        assertEquals(1, ex.getIndex());
        assertInstanceOf(EventGapException.class, ex.getCause());
        assertEquals(1, checkpoint(s1), "o prefixo (s1 v1) está comitado");
        assertArrayEquals(new long[] {1, 1}, estado(s1));
        assertEquals(0, checkpoint(s2), "a mensagem culpada não avança nada, nem a v1");
        assertEquals(0, acks.get(), "sem ack: quem comita o offset do prefixo é o error handler");

        // o handler reentrega de i em diante (depois do DLT da culpada): a que sobra é aplicada normalmente
        assertEquals(1, aplicadas(consumer.onBatch(proj, lote(s1v2), ack)));
        assertArrayEquals(new long[] {2, 3}, estado(s1));
    }

    @Test
    void payloadInvalidoNoMeioDoLoteAplicaAsValidasAnterioresEAcusaOIndice() throws Exception {
        String s = "s-" + UUID.randomUUID();
        EventEnvelope v1 = append(s, 1, 1);
        EventEnvelope v2 = append(s, 2, 2);
        List<String> mensagens = lote(v1);
        mensagens.add("isto-nao-e-json");
        mensagens.addAll(lote(v2));

        BatchListenerFailedException ex = assertThrows(BatchListenerFailedException.class,
                () -> consumer.onBatch(proj, mensagens, ack));

        assertEquals(1, ex.getIndex());
        assertInstanceOf(InvalidEventMessageException.class, ex.getCause());
        assertEquals(1, checkpoint(s), "só a v1, que veio antes do lixo");
        assertEquals(0, acks.get());
    }

    @Test
    void falhaTransitoriaDaProjecaoNoMeioDesfazSoAMensagemCulpadaEMantemOPrefixo() throws Exception {
        String ok = "ok-" + UUID.randomUUID();
        String ruim = "ruim-" + UUID.randomUUID();
        EventEnvelope ok1 = append(ok, 1, 1);
        EventEnvelope ruim1 = append(ruim, 1, 1);
        EventEnvelope ok2 = append(ok, 2, 2);
        Projection quebraNoRuim = new TestSomatorio() {
            @Override
            public void apply(Connection conn, EventEnvelope e, String schema) throws SQLException {
                if (e.streamId().equals(ruim)) {
                    throw new SQLException("conexão caiu (simulado)");
                }
                super.apply(conn, e, schema);
            }
        };

        BatchListenerFailedException ex = assertThrows(BatchListenerFailedException.class,
                () -> consumer.onBatch(quebraNoRuim, lote(ok1, ruim1, ok2), ack));

        assertEquals(1, ex.getIndex());
        assertInstanceOf(SQLException.class, ex.getCause());
        assertEquals(1, checkpoint(ok), "prefixo comitado; a v2 (depois da culpada) não foi aplicada");
        assertEquals(0, checkpoint(ruim), "a culpada foi desfeita junto com o checkpoint dela");
        assertEquals(0, acks.get());

        // reentrega do lote a partir da culpada (o prefixo já está aplicado): o retry aplica o resto
        assertEquals(2, aplicadas(consumer.onBatch(proj, lote(ok1, ruim1, ok2), ack)), "ok1 é reenvio, o resto aplica");
        assertEquals(2, checkpoint(ok));
        assertEquals(1, checkpoint(ruim));
    }

    @Test
    void aplicadoresConcorrentesDeLotesNuncaContamOMesmoEventoDuasVezes() throws Exception {
        String s = "s-" + UUID.randomUUID();
        EventEnvelope[] msgs = new EventEnvelope[5];
        for (int v = 1; v <= 5; v++) {
            msgs[v - 1] = append(s, v, v);
        }
        // todos os aplicadores recebem o MESMO lote, na mesma ordem: a ordem de aquisição dos locks de linha é a
        // mesma (em produção há 1 consumer; lotes em ordens diferentes poderiam dar deadlock, 40P01, e o retry)
        List<String> mensagens = lote(msgs);
        int aplicadores = 8;
        CyclicBarrier largada = new CyclicBarrier(aplicadores);
        ExecutorService pool = Executors.newFixedThreadPool(aplicadores);
        try {
            List<Future<Long>> rs = new ArrayList<>();
            for (int i = 0; i < aplicadores; i++) {
                rs.add(pool.submit(() -> {
                    largada.await();
                    return aplicadas(consumer.onBatch(proj, mensagens, ack));
                }));
            }
            long total = 0;
            for (Future<Long> f : rs) {
                total += f.get();
            }
            assertEquals(5, total, "cada versão aplicada por exatamente um aplicador");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(5, checkpoint(s));
        assertArrayEquals(new long[] {5, 15}, estado(s));
        assertEquals(aplicadores, acks.get(), "todos os lotes comitaram e deram ack");
    }
}
