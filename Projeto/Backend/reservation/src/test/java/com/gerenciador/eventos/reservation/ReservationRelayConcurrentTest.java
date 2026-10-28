package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.TestSupport.EventsTestSupport;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventStore;
import com.gerenciador.eventos.shared.events.NovoEvento;
import com.gerenciador.eventos.shared.events.OutboxRelay;

/**
 * Relay do outbox do schema {@code reservation} com 2+ réplicas (issue #111): 3 relays (instâncias separadas, lote de 4
 * para o dreno levar várias passadas) drenam AO MESMO TEMPO o mesmo outbox contra o Redpanda real.
 * Cada evento sai uma única vez e, por stream, na ordem de versão. O relay é a mesma classe do {@code shared} que o
 * {@code OutboxRelayTest} do monólito cobre no schema {@code public}; aqui fica provado no schema do reservation (chave de
 * advisory lock própria, derivada do schema), que é onde a autoescala vai rodar réplicas. Os relays são criados na mão: o
 * timer do relay do contexto fica desligado ({@code events.relay.enabled} default {@code false}) e só estes drenam.
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
public class ReservationRelayConcurrentTest {
    private static final String TOPICO = "t111.relay";
    private static final int STREAMS = 3;
    private static final int VERSOES = 15;
    private static final int REPLICAS = 3;

    @Autowired private KafkaTemplate<String, String> template;
    @Autowired private EventStore eventStore;
    @Autowired private DatabaseConnection db;
    @Autowired private ObjectMapper json;
    @Value("${spring.kafka.bootstrap-servers}") private String bootstrap;

    @BeforeEach
    void limpar() throws Exception {
        EventsTestSupport.limpar(db);
    }

    private void append(String rodada, String stream, int version) throws Exception {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            eventStore.append(c, NovoEvento.of(TOPICO, rodada + stream, version, "Teste",
                    json.createObjectNode().put("v", version), rodada + stream)).orElseThrow();
            c.commit();
        }
    }

    /** Linhas do outbox da rodada (o outbox é compartilhado com outros testes: contar tudo dependeria deles). */
    private long contarOutbox(String rodada) throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from reservation.event_outbox ob join reservation.event_store es "
                     + "on es.global_position = ob.global_position where es.stream_id like '" + rodada + "%'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @RepeatedTest(3)
    public void tresRelaysAoMesmoTempoPublicamCadaEventoUmaSoVezEmOrdemPorStream() throws Exception {
        assertEquals("reservation", eventStore.schema(), "o teste mira o outbox do schema reservation");
        String rodada = UUID.randomUUID().toString().substring(0, 8) + "-";
        int total = STREAMS * VERSOES;
        List<String> ordemDoAppend = new ArrayList<>();
        for (int v = 1; v <= VERSOES; v++) {
            for (int s = 0; s < STREAMS; s++) {
                append(rodada, "S" + s, v);
                ordemDoAppend.add("S" + s + ":" + v);
            }
        }
        assertEquals(total, contarOutbox(rodada));

        List<Callable<Integer>> relays = new ArrayList<>();
        for (int i = 0; i < REPLICAS; i++) {
            OutboxRelay relay = new OutboxRelay(db, eventStore, json, template, 4);
            relays.add(relay::drain);
        }
        Concorrencia.emParalelo(relays);

        assertEquals(0, contarOutbox(rodada), "outbox da rodada esvaziado");
        // pede UMA a mais que o total: se algum evento tivesse saído em dobro, ela apareceria e o assert abaixo falharia
        List<ConsumerRecord<String, String>> msgs = EventsTestSupport.lerTopico(bootstrap, TOPICO, total + 1,
                Duration.ofSeconds(8), r -> r.value().contains("\"streamId\":\"" + rodada));
        assertEquals(total, msgs.size(), "exatamente uma mensagem por evento no tópico");
        // 1 partição e cada relay só lê depois do commit do anterior: a ordem no tópico é a de global_position (= a do append)
        List<String> ordemNoTopico = new ArrayList<>();
        Map<String, List<Integer>> versoesPorStream = new HashMap<>();
        for (ConsumerRecord<String, String> r : msgs) {
            JsonNode env = json.readTree(r.value());
            ordemNoTopico.add(env.get("streamId").asText().substring(rodada.length()) + ":" + env.get("version").asInt());
            versoesPorStream.computeIfAbsent(env.get("streamId").asText(), k -> new ArrayList<>())
                    .add(env.get("version").asInt());
        }
        assertEquals(ordemDoAppend, ordemNoTopico, "ordem global do tópico = ordem por global_position");
        assertEquals(STREAMS, versoesPorStream.size());
        List<Integer> esperado = IntStream.rangeClosed(1, VERSOES).boxed().toList();
        versoesPorStream.forEach((stream, versoes) -> assertEquals(esperado, versoes, "ordem por stream: " + stream));
    }
}
