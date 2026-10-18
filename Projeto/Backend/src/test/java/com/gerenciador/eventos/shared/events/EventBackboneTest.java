package com.gerenciador.eventos.shared.events;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.annotation.DirtiesContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.TestSupport.EventsTestSupport;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Caminho ponta a ponta do backbone (issue #27), tudo ligado por flag: append -> outbox -> relay ({@code @Scheduled}
 * de 50 ms) -> tópico no Redpanda -> {@code @KafkaListener} (ack manual) -> consumer idempotente -> projeção; e a
 * mensagem que não dá para aplicar (buraco) indo para o dead-letter topic depois do retry.
 * {@code @DirtiesContext}: o relay de 50 ms e o listener não podem sobrar no cache de contextos e disputar o outbox
 * com os testes seguintes.
 */
@SpringBootTest(properties = {
        "events.relay.enabled=true", "events.relay.interval-ms=50",
        "events.consumer.enabled=true", "events.consumer.retry.interval-ms=200", "events.consumer.retry.max-attempts=2"})
@Import(GlobalDbTruncator.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class EventBackboneTest {

    private static final String TOPICO = "t27.e2e";
    private static final String LISTENER_ID = "t27-somatorio";

    /** Streams cujo apply falha com SQLException N vezes antes de funcionar (queda transitória de banco simulada). */
    static final Map<String, AtomicInteger> FALHAS_RESTANTES = new ConcurrentHashMap<>();

    static class SomatorioInstavel extends TestSomatorio {
        @Override
        public void apply(Connection conn, EventEnvelope e, String schema) throws SQLException {
            AtomicInteger restantes = FALHAS_RESTANTES.get(e.streamId());
            if (restantes != null && restantes.getAndDecrement() > 0) {
                throw new SQLException("conexão caiu (simulado)");
            }
            super.apply(conn, e, schema);
        }
    }

    /**
     * Listener de teste: o que #28/#29 farão para cada projeção de verdade. {@code idIsGroup = false} é obrigatório:
     * por padrão o {@code id} do {@code @KafkaListener} vira o {@code group.id}, e aí o
     * {@code spring.kafka.consumer.group-id} (e o {@code EVENTS_CONSUMER_GROUP} do run-tests.sh) não valeria.
     */
    static class ListenerSomatorio {
        private final IdempotentConsumer consumer;
        private final Projection projecao = new SomatorioInstavel();

        ListenerSomatorio(IdempotentConsumer consumer) {
            this.consumer = consumer;
        }

        @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = TOPICO)
        void ouvir(String value, Acknowledgment ack) throws Exception {
            consumer.onMessage(projecao, value, ack);
        }
    }

    @AfterAll
    void derrubarTabelaDeTeste() throws Exception {
        TestSomatorio.derrubarTabela(db);
    }

    @TestConfiguration
    static class Config {
        @Bean
        NewTopic topico() {
            return TopicBuilder.name(TOPICO).partitions(1).replicas(1).build();
        }

        @Bean
        ListenerSomatorio listenerSomatorio(IdempotentConsumer consumer) {
            return new ListenerSomatorio(consumer);
        }
    }

    @Autowired private EventStore eventStore;
    @Autowired private DatabaseConnection db;
    @Autowired private ObjectMapper json;
    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired private EventReplay replay;
    @Autowired private KafkaTemplate<String, String> template;
    @Value("${spring.kafka.bootstrap-servers}") private String bootstrap;
    @Value("${spring.kafka.consumer.group-id}") private String grupoConfigurado;

    private final String rodada = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void preparar() throws Exception {
        TestSomatorio.criarTabela(db);
        EventsTestSupport.limpar(db);
        aguardarAtribuicaoDeParticao();
    }

    /**
     * Espera a atribuição de partição antes de appendar. Com {@code earliest} (o padrão do projeto) nada seria pulado,
     * mas com {@code latest} um evento publicado antes da atribuição se perderia; o helper vale para os dois e evita
     * medir o rebalance inicial como se fosse lag.
     */
    private void aguardarAtribuicaoDeParticao() throws Exception {
        MessageListenerContainer container = registry.getListenerContainer(LISTENER_ID);
        EventsTestSupport.aguardar(Duration.ofSeconds(60),
                () -> container.getAssignedPartitions() != null && !container.getAssignedPartitions().isEmpty(),
                "atribuição de partição do listener");
    }

    private void append(String stream, int version, int valor) throws Exception {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            eventStore.append(c, NovoEvento.of(TOPICO, stream, version, "Teste",
                    json.createObjectNode().put("valor", valor), "ev-1")).orElseThrow();
            c.commit();
        }
    }

    private int checkpoint(String stream) throws Exception {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT version FROM public.projection_checkpoint "
                     + "WHERE stream_type = ? AND stream_id = ?")) {
            ps.setString(1, TOPICO);
            ps.setString(2, stream);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private boolean outboxVazio() throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from public.event_outbox")) {
            rs.next();
            return rs.getLong(1) == 0;
        }
    }

    @Test
    void listenerUsaOGroupIdConfiguradoEnaoOIdDoListener() {
        assertEquals(grupoConfigurado, registry.getListenerContainer(LISTENER_ID).getGroupId());
    }

    @Test
    void appendChegaNaProjecaoPeloRelayEPeloBrokerEOReplayBate() throws Exception {
        String a = rodada + "A";
        String b = rodada + "B";
        for (int v = 1; v <= 4; v++) {
            append(a, v, v);       // soma 10
            append(b, v, 10 * v);  // soma 100
        }

        EventsTestSupport.aguardar(Duration.ofSeconds(30),
                () -> {
                    try {
                        return outboxVazio() && checkpoint(a) == 4 && checkpoint(b) == 4;
                    } catch (Exception e) {
                        return false;
                    }
                }, "outbox esvaziado e projeção dos 2 streams em v4");

        assertArrayEquals(new long[] {4, 10}, TestSomatorio.ler(db, "public", a));
        assertArrayEquals(new long[] {4, 100}, TestSomatorio.ler(db, "public", b));
        replay.rebuild(new TestSomatorio(), TOPICO, "replay_t27_e2e");
        try {
            assertEquals(List.of(), replay.divergentTables(new TestSomatorio(), "replay_t27_e2e"));
        } finally {
            replay.drop("replay_t27_e2e");
        }
    }

    @Test
    void erroTransitorioDeBancoNuncaVaiParaODeadLetterTopicEOConsumerSeRecupera() throws Exception {
        String s = rodada + "TRANS";
        FALHAS_RESTANTES.put(s, new AtomicInteger(4)); // mais que events.consumer.retry.max-attempts (2): retry é ilimitado
        append(s, 1, 5);

        EventsTestSupport.aguardar(Duration.ofSeconds(30), () -> {
            try {
                return checkpoint(s) == 1;
            } catch (Exception e) {
                return false;
            }
        }, "consumer se recuperar da falha transitória e aplicar o evento");

        assertArrayEquals(new long[] {1, 5}, TestSomatorio.ler(db, "public", s));
        assertEquals(0, EventsTestSupport.lerTopico(bootstrap, TOPICO + ".DLT", 1, Duration.ofSeconds(2),
                r -> r.value().contains("\"streamId\":\"" + s + "\"")).size(), "transitório não vai ao DLT");
    }

    @Test
    void payloadInvalidoVaiDiretoParaODeadLetterTopicSemTravarOConsumer() throws Exception {
        String lixo = "isto-nao-e-json-" + rodada;
        String ok = rodada + "AFTERJSON";
        template.send(TOPICO, "ev-1", lixo).get();
        append(ok, 1, 3);

        List<ConsumerRecord<String, String>> naDlt = EventsTestSupport.lerTopico(bootstrap, TOPICO + ".DLT", 1,
                Duration.ofSeconds(30), r -> lixo.equals(r.value()));
        assertEquals(1, naDlt.size(), "JSON inválido vai para o DLT");
        EventsTestSupport.aguardar(Duration.ofSeconds(30), () -> {
            try {
                return checkpoint(ok) == 1;
            } catch (Exception e) {
                return false;
            }
        }, "consumer seguir depois do payload inválido");
    }

    @Test
    void mensagemComBuracoQueNaoExisteNoEventStoreVaiParaODeadLetterTopicDepoisDoRetry() throws Exception {
        String dlt = rodada + "DLT";
        String ok = rodada + "OK";
        append(dlt, 1, 1);
        append(dlt, 3, 3); // a v2 nunca existe: a v3 não pode ser aplicada
        append(ok, 1, 7);  // chega depois da mensagem envenenada: o consumer não pode travar nela

        List<ConsumerRecord<String, String>> naDlt = EventsTestSupport.lerTopico(bootstrap, TOPICO + ".DLT", 1,
                Duration.ofSeconds(60), r -> r.value().contains("\"streamId\":\"" + dlt + "\""));
        assertEquals(1, naDlt.size(), "mensagem esgotou o retry e foi para <tópico>.DLT");

        EventsTestSupport.aguardar(Duration.ofSeconds(30), () -> {
            try {
                return checkpoint(ok) == 1;
            } catch (Exception e) {
                return false;
            }
        }, "consumer seguiu para o próximo stream");
        assertEquals(1, checkpoint(dlt), "a v3 não avançou o checkpoint");
        assertTrue(TestSomatorio.ler(db, "public", dlt)[0] == 1);
    }
}
