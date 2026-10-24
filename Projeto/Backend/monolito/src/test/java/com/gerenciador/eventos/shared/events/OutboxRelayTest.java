package com.gerenciador.eventos.shared.events;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.TestSupport.EventsTestSupport;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Relay outbox -> Redpanda (issue #27) contra Postgres e Redpanda reais. O {@code @Scheduled} fica com intervalo
 * de 1h: o teste dirige o relay na mão ({@code drain()}), sem corrida com o timer.
 */
@SpringBootTest(properties = {"events.relay.enabled=true", "events.relay.interval-ms=3600000"})
@Import(GlobalDbTruncator.class)
public class OutboxRelayTest {

    private static final String TOPICO = "t27.relay";

    @Autowired private OutboxRelay relay;
    @Autowired private KafkaTemplate<String, String> template;
    @Autowired private EventStore eventStore;
    @Autowired private DatabaseConnection db;
    @Autowired private ObjectMapper json;
    @Value("${spring.kafka.bootstrap-servers}") private String bootstrap;

    private final String rodada = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void limpar() throws Exception {
        EventsTestSupport.limpar(db);
    }

    private void append(String stream, int version, String chave) throws Exception {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            eventStore.append(c, NovoEvento.of(TOPICO, rodada + stream, version, "Teste",
                    json.createObjectNode().put("v", version), chave)).orElseThrow();
            c.commit();
        }
    }

    private long contarOutbox() throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from public.event_outbox")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private List<ConsumerRecord<String, String>> lerDestaRodada(int esperadas, Duration timeout) throws Exception {
        return EventsTestSupport.lerTopico(bootstrap, TOPICO, esperadas, timeout,
                r -> r.value().contains("\"streamId\":\"" + rodada));
    }

    @Test
    void publicaNoTopicoNaOrdemPorStreamComChaveEHeaderEApagaOOutbox() throws Exception {
        // 3 streams intercalados; chave = id do evento de domínio (aqui "ev-A"/"ev-B"/"ev-C")
        for (int v = 1; v <= 3; v++) {
            append("A", v, "ev-A");
            append("B", v, "ev-B");
            append("C", v, "ev-C");
        }
        assertEquals(9, contarOutbox());

        assertEquals(9, relay.drain());

        assertEquals(0, contarOutbox(), "lote publicado é apagado do outbox");
        List<ConsumerRecord<String, String>> msgs = lerDestaRodada(9, Duration.ofSeconds(20));
        assertEquals(9, msgs.size());
        Map<String, List<Integer>> versoesPorStream = new HashMap<>();
        for (ConsumerRecord<String, String> r : msgs) {
            JsonNode env = json.readTree(r.value());
            assertEquals("ev-" + env.get("streamId").asText().substring(rodada.length()), r.key(),
                    "chave da mensagem = partition_key do append");
            assertEquals(TOPICO, env.get("streamType").asText());
            assertNotNull(r.headers().lastHeader("publishedAt"), "relay acrescenta publishedAt");
            Instant publicado = Instant.parse(new String(r.headers().lastHeader("publishedAt").value()));
            assertTrue(publicado.isAfter(Instant.parse(env.get("occurredAt").asText())), "publishedAt > occurredAt");
            versoesPorStream.computeIfAbsent(env.get("streamId").asText(), k -> new ArrayList<>())
                    .add(env.get("version").asInt());
        }
        assertEquals(3, versoesPorStream.size());
        versoesPorStream.values().forEach(v -> assertEquals(List.of(1, 2, 3), v, "ordem por stream"));
    }

    @Test
    void falhaNoEnvioFazRollbackEOLoteVoltaNoProximoTick() throws Exception {
        append("F", 1, "ev-F");
        Map<String, Object> props = new HashMap<>();
        props.put("bootstrap.servers", "localhost:1"); // nada escuta aqui: o envio estoura o max.block.ms
        props.put("key.serializer", StringSerializer.class);
        props.put("value.serializer", StringSerializer.class);
        props.put("max.block.ms", 500);
        DefaultKafkaProducerFactory<String, String> fabricaSemBroker = new DefaultKafkaProducerFactory<>(props);
        try {
            OutboxRelay semBroker = new OutboxRelay(db, eventStore, json, new KafkaTemplate<>(fabricaSemBroker), 200);
            assertThrows(Exception.class, semBroker::drain);
        } finally {
            fabricaSemBroker.destroy(); // fecha o producer: não deixa thread de rede tentando localhost:1 pelo resto da suíte
        }

        assertEquals(1, contarOutbox(), "falha no envio: nada apagado");
        assertEquals(1, relay.drain(), "o relay saudável publica o que sobrou");
        assertEquals(0, contarOutbox());
    }

    @Test
    void relayConcorrenteSemOLockNaoPublicaNada() throws Exception {
        append("L", 1, "ev-L");
        try (Connection dono = db.getConnection()) {
            dono.setAutoCommit(false);
            try (Statement st = dono.createStatement();
                 ResultSet rs = st.executeQuery("select pg_try_advisory_xact_lock(" + OutboxRelay.LOCK_KEY + ")")) {
                rs.next();
                assertTrue(rs.getBoolean(1), "este teste pega o lock primeiro");
            }
            assertEquals(0, relay.drain(), "quem não pega o lock sai sem publicar");
            assertEquals(1, contarOutbox());
            dono.commit(); // libera o lock de transação
        }
        assertEquals(1, relay.drain());
    }

    @Test
    void doisRelaysAoMesmoTempoPublicamCadaEventoUmaSoVezEmOrdem() throws Exception {
        for (int v = 1; v <= 20; v++) {
            append("R", v, "ev-R");
        }
        OutboxRelay outro = new OutboxRelay(db, eventStore, json, template, 200);
        CyclicBarrier largada = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> { largada.await(); return relay.drain(); });
            Future<Integer> b = pool.submit(() -> { largada.await(); return outro.drain(); });
            assertEquals(20, a.get() + b.get(), "soma publicada = 20: ninguém publica em dobro");
        } finally {
            pool.shutdownNow();
        }
        List<ConsumerRecord<String, String>> msgs = lerDestaRodada(20, Duration.ofSeconds(20));
        List<Integer> versoes = new ArrayList<>();
        for (ConsumerRecord<String, String> r : msgs) {
            versoes.add(json.readTree(r.value()).get("version").asInt());
        }
        // lista exata 1..20, em ordem: nenhuma duplicata nem versão fora do lugar
        assertEquals(java.util.stream.IntStream.rangeClosed(1, 20).boxed().toList(), versoes);
    }
}
