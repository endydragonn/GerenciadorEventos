package com.gerenciador.eventos.catalog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.TestSupport.FakeEventCapServer;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.catalogclient.api.Event;
import com.gerenciador.eventos.catalogclient.api.EventoEmConflitoException;
import com.gerenciador.eventos.catalogclient.api.EventoNaoEncontradoException;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventReplay;
import com.gerenciador.eventos.shared.events.EventStore;
import com.gerenciador.eventos.shared.events.NovoEvento;

/**
 * CQRS + Event Sourcing do agregado {@code Event} no SERVIÇO catalog (issues #28 e #35): a mesma prova do monólito, agora no
 * schema {@code catalog} (event store próprio). Cada escrita appenda no stream {@code catalog.event} na transação da projeção
 * inline ({@code catalog.event}/{@code catalog.eventpresenter}); conflito de versão repete o command; o replay do
 * {@code catalog.event_store} na sombra == projeção viva nas colunas comparáveis (sem {@code image_data} nem carimbos). O
 * criador é só um id (sem FK para {@code users}). O backfill da migração {@code 20261022} (reescrita no cutover da #35 para
 * agir no schema em que {@code event} está) é exercitado aqui, no schema {@code catalog}.
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CatalogEventSourcingTest {

    private static final String SOMBRA = "replay_t35";
    private static final long CRIADOR = 4242L;
    /** Colunas que o log determina (image_data é blob fora do ES; created_at/updated_at são carimbos do banco). */
    private static final Map<String, List<String>> COMPARAVEIS = Map.of("event", List.of(
            "event_id", "creator_id", "event_name", "ead", "address", "event_date", "description", "version"));

    @Autowired private EventService eventService;
    @Autowired private DatabaseConnection db;
    @Autowired private EventStore eventStore;
    @Autowired private EventReplay replay;
    @Autowired private CatalogEventProjection projection;
    @Autowired private CatalogEventWriter writer;
    @Autowired private ObjectMapper json;

    @BeforeEach
    void preparar() throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE TABLE catalog.event, catalog.event_store, catalog.event_outbox, "
                    + "catalog.event_snapshot, catalog.projection_checkpoint CASCADE");
        }
        FakeEventCapServer.get().reset();
    }

    @AfterEach
    void derrubarSombra() throws Exception {
        replay.drop(SOMBRA);
    }

    private Event novo(String nome, int capacidade, String... apresentadores) {
        Event e = new Event();
        e.setCreator_id(CRIADOR);
        e.setEvent_name(nome);
        e.setIs_EAD(true);
        e.setEvent_date(LocalDateTime.now().plusDays(30).truncatedTo(ChronoUnit.SECONDS));
        e.setLot_quantity(capacidade);
        e.setDescription("desc " + nome);
        e.setPresenters(new ArrayList<>(List.of(apresentadores)));
        return e;
    }

    /** Tipos de evento do stream, em ordem de versão (confere também que as versões são 1..n sem buraco). */
    private List<String> tiposDoStream(long eventId) throws Exception {
        List<String> tipos = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT version, event_type FROM catalog.event_store "
                     + "WHERE stream_type = 'catalog.event' AND stream_id = ? ORDER BY version")) {
            ps.setString(1, String.valueOf(eventId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    assertEquals(tipos.size() + 1, rs.getInt(1), "versões contíguas a partir de 1");
                    tipos.add(rs.getString(2));
                }
            }
        }
        return tipos;
    }

    private long consultar(String sql) throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void atualizar(Event e, String descricao, String... apresentadores) {
        e.setDescription(descricao);
        e.setPresenters(new ArrayList<>(List.of(apresentadores)));
        eventService.updateEvent(e);
    }

    @Test
    void replayDoAgregadoEventReconstroiAProjecaoNaSombra() throws Exception {
        Event e1 = eventService.createEvent(novo("ES um", 10, "Ana", "Bia"));
        Event e2 = eventService.createEvent(novo("ES dois", 5));
        Event e3 = eventService.createEvent(novo("ES três ilimitado", -1, "Dani"));
        Event e4 = eventService.createEvent(novo("ES quatro", 20, "Eva"));

        atualizar(e1, "descrição nova", "Bia", "Caio");           // Removed Ana + Added Caio
        e1.setLot_quantity(12);
        e1.setEvent_name("ES um renomeado");
        eventService.updateEvent(e1);                              // EventUpdated (nome + capacidade), sem mexer em apresentadores
        eventService.updateEvent(e2);
        eventService.saveImage(e2.getEvent_id(), new byte[] {1, 2, 3}); // imagem: blob fora do ES, método próprio
        atualizar(e3, "ilimitado editado", "Dani", "Fábio");
        eventService.delete(e4.getEvent_id());                     // EventCancelled

        assertEquals(List.of("EventCreated", "EventUpdated", "PresenterRemoved", "PresenterAdded", "EventUpdated"),
                tiposDoStream(e1.getEvent_id()));
        assertEquals(List.of("EventCreated", "EventUpdated"), tiposDoStream(e2.getEvent_id()));
        assertEquals(List.of("EventCreated", "EventUpdated", "PresenterAdded"), tiposDoStream(e3.getEvent_id()));
        assertEquals(List.of("EventCreated", "EventCancelled"), tiposDoStream(e4.getEvent_id()));
        assertEquals(5, consultar("SELECT version FROM catalog.event WHERE event_id = " + e1.getEvent_id()),
                "a versão da projeção é a do último evento do stream");
        assertEquals(0, consultar("SELECT count(*) FROM catalog.event WHERE event_id = " + e4.getEvent_id()),
                "cancelado = linha removida");
        assertEquals(0, consultar("SELECT count(*) FROM catalog.eventpresenter WHERE event_id = " + e4.getEvent_id()),
                "os apresentadores do cancelado caíram junto");
        assertNull(FakeEventCapServer.get().capacidade(e4.getEvent_id()),
                "o eventcap do cancelado foi removido do serviço reservation (chamada síncrona, sem CASCADE)");

        replay.rebuild(projection, CatalogEventProjection.STREAM_TYPE, SOMBRA);

        assertEquals(List.of(), replay.divergentTables(projection, SOMBRA, COMPARAVEIS),
                "event/eventpresenter reconstruídas do catalog.event_store == vivas (colunas comparáveis)");
        assertEquals(3, consultar("SELECT count(*) FROM " + SOMBRA + ".event"));
        assertEquals(1, consultar("SELECT count(*) FROM catalog.event WHERE image_data IS NOT NULL"),
                "a imagem vive só na tabela viva");
        assertEquals(0, consultar("SELECT count(*) FROM " + SOMBRA + ".event WHERE image_data IS NOT NULL"),
                "e não vem do log");

        // a comparação detecta divergência de verdade
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE catalog.event SET event_name = event_name || ' x' WHERE event_id = " + e2.getEvent_id());
        }
        assertEquals(List.of("event"), replay.divergentTables(projection, SOMBRA, COMPARAVEIS));
    }

    @Test
    void payloadsNaoTemPiiNemImagemEGuardamValoresCalculados() throws Exception {
        Event e = novo("ES payload", 7, "Gil");
        e.setImage_data(new byte[] {9, 9, 9});
        e.setBuy_time_limit(null);                                 // calculado: assume a data do evento
        Event salvo = eventService.createEvent(e);

        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT payload::text, partition_key, stream_id FROM catalog.event_store")) {
            assertTrue(rs.next());
            String payload = rs.getString(1);
            assertEquals(String.valueOf(salvo.getEvent_id()), rs.getString(2), "chave da mensagem = id do evento");
            assertEquals(String.valueOf(salvo.getEvent_id()), rs.getString(3));
            assertFalse(payload.contains("@"), "sem e-mail/PII: " + payload);
            assertFalse(payload.toLowerCase().contains("image"), "image_data fica fora do ES: " + payload);
            var p = json.readTree(payload);
            assertEquals(CRIADOR, p.get("creatorId").asLong(), "só o id do criador");
            assertEquals(7, p.get("capacity").asInt(), "o reservation reconcilia o eventcap por este campo (envelope congelado)");
            assertEquals(salvo.getEvent_date().toString(), p.get("buyTimeLimit").asText(), "valor calculado no command");
            assertEquals("Gil", p.get("presenters").get(0).asText());
            assertFalse(rs.next());
        }
        assertEquals(1, consultar("SELECT count(*) FROM catalog.event_outbox"), "outbox na mesma transação do append");
        assertEquals(7, FakeEventCapServer.get().capacidade(salvo.getEvent_id()).getCapacity(),
                "o eventcap nasceu no reservation depois do commit");
    }

    @Test
    void conflitoDeVersaoRepeteOCommandENoLimiteNaoGravaNada() throws Exception {
        Event e = eventService.createEvent(novo("ES conflito", 3));
        long id = e.getEvent_id();

        // 1) conflito transitório: alguém appenda a v2 por fora; na 2ª tentativa ela some e o command passa
        AtomicInteger tentativas = new AtomicInteger();
        writer.executar(tx -> {
            tx.bloquear(id);
            if (tentativas.incrementAndGet() == 1) {
                appendPorFora(id, 2);
            } else {
                try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
                    st.execute("DELETE FROM catalog.event_store WHERE stream_id = '" + id + "' AND version = 2");
                }
            }
            tx.append("PresenterAdded", json.createObjectNode().put("eventId", id).put("presenterName", "Zé"));
            return null;
        });
        assertEquals(2, tentativas.get(), "conflito -> rollback e o command roda de novo");
        assertEquals(List.of("EventCreated", "PresenterAdded"), tiposDoStream(id));
        assertEquals(2, consultar("SELECT version FROM catalog.event WHERE event_id = " + id));

        // 2) conflito permanente: a v3 existe no log mas não na projeção -> esgota as tentativas, nada é gravado
        appendPorFora(id, 3);
        long antes = consultar("SELECT count(*) FROM catalog.event_store");
        e.setDescription("não pode gravar");
        EventoEmConflitoException ex = assertThrows(EventoEmConflitoException.class, () -> eventService.updateEvent(e));
        assertTrue(ex.getMessage().contains("Conflito de versão"), ex.getMessage());
        assertEquals(antes, consultar("SELECT count(*) FROM catalog.event_store"));
        assertEquals("desc ES conflito", eventService.findById(id).getDescription());
        assertEquals(2, consultar("SELECT version FROM catalog.event WHERE event_id = " + id));
    }

    private void appendPorFora(long id, int versao) throws SQLException {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            eventStore.append(c, NovoEvento.of("catalog.event", String.valueOf(id), versao, "EventUpdated",
                    json.createObjectNode().put("eventId", id), String.valueOf(id))).orElseThrow();
            c.commit();
        }
    }

    @Test
    void updatesConcorrentesNoMesmoEventoSerializamEMantemOLogContiguo() throws Exception {
        Event base = eventService.createEvent(novo("ES concorrente", 50, "Hugo"));
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                fs.add(pool.submit(() -> {
                    Event e = novo("ES concorrente", 50, "Hugo");
                    e.setEvent_id(base.getEvent_id());
                    e.setDescription("v" + k);
                    return eventService.updateEvent(e);
                }));
            }
            for (Future<?> f : fs) {
                assertNotNull(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        List<String> tipos = tiposDoStream(base.getEvent_id());
        assertEquals(1 + n, tipos.size(), "1 EventCreated + 1 EventUpdated por update, sem versão perdida");
        assertEquals(1 + n, consultar("SELECT version FROM catalog.event WHERE event_id = " + base.getEvent_id()));

        replay.rebuild(projection, CatalogEventProjection.STREAM_TYPE, SOMBRA);
        assertEquals(List.of(), replay.divergentTables(projection, SOMBRA, COMPARAVEIS));
    }

    /** Resultado de uma chamada concorrente: sucesso, 409 (conflito de versão esgotado) ou 404 (evento apagado no meio). */
    private enum Resultado { OK, CONFLITO_409, NAO_ENCONTRADO_404 }

    private Resultado tentar(Runnable chamada) {
        try {
            chamada.run();
            return Resultado.OK;
        } catch (EventoEmConflitoException ex) {
            return Resultado.CONFLITO_409;
        } catch (EventoNaoEncontradoException ex) {
            return Resultado.NAO_ENCONTRADO_404;
        }
    }

    private long contarTipo(long eventId, String tipo) throws Exception {
        return consultar("SELECT count(*) FROM catalog.event_store WHERE stream_type = 'catalog.event' AND stream_id = '"
                + eventId + "' AND event_type = '" + tipo + "'");
    }

    /**
     * N réplicas do {@code catalog} (autoescala, issue #112) mandando commands DIFERENTES ao MESMO evento ao mesmo tempo (cada thread na
     * sua conexão; todas largam juntas): {@code EventUpdated} com conjuntos de apresentadores diferentes, o que gera
     * {@code PresenterAdded}/{@code PresenterRemoved} por diferença. Cada chamada ou conclui ou vira 409 (conflito esgotado), nunca grava
     * parcial: o stream fica com versões 1..n contíguas, {@code event.version} = n, um {@code EventUpdated} por chamada bem-sucedida,
     * o saldo de apresentadores bate com a projeção, o outbox tem uma linha por evento (nenhuma duplicada) e o replay reconstrói a viva.
     */
    @RepeatedTest(5)
    void commandsDiferentesConcorrentesNoMesmoEventoNaoDuplicamNemPulamVersao() throws Exception {
        Event base = eventService.createEvent(novo("ES réplicas", 50, "Hugo"));
        long id = base.getEvent_id();
        List<String[]> conjuntos = List.of(new String[] {"Hugo"}, new String[] {"Hugo", "Ana"}, new String[] {"Bia"},
                new String[] {}, new String[] {"Ana", "Bia", "Caio"}, new String[] {"Hugo", "Caio"}, new String[] {"Dani"},
                new String[] {"Ana"});
        int n = conjuntos.size();
        CyclicBarrier largada = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Resultado> resultados = new ArrayList<>();
        try {
            List<Future<Resultado>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                fs.add(pool.submit(() -> {
                    Event e = novo("ES réplicas", 50, conjuntos.get(k));
                    e.setEvent_id(id);
                    e.setDescription("réplica " + k);
                    largada.await();
                    return tentar(() -> eventService.updateEvent(e));
                }));
            }
            for (Future<Resultado> f : fs) {
                resultados.add(f.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        long ok = resultados.stream().filter(r -> r == Resultado.OK).count();
        assertTrue(ok >= 1, "ao menos uma chamada vence (senão as invariantes abaixo passariam vazias): " + resultados);
        assertTrue(resultados.stream().noneMatch(r -> r == Resultado.NAO_ENCONTRADO_404), "ninguém apagou o evento: " + resultados);
        List<String> tipos = tiposDoStream(id); // também confere versões 1..n sem buraco
        assertEquals(ok, contarTipo(id, "EventUpdated"), "um EventUpdated por chamada bem-sucedida (409 não grava nada): " + resultados);
        assertEquals(1 + ok + contarTipo(id, "PresenterAdded") + contarTipo(id, "PresenterRemoved"), tipos.size());
        assertEquals(tipos.size(), consultar("SELECT version FROM catalog.event WHERE event_id = " + id), "event.version = último evento");
        long apresentadores = consultar("SELECT count(*) FROM catalog.eventpresenter WHERE event_id = " + id);
        assertEquals(1 + contarTipo(id, "PresenterAdded") - contarTipo(id, "PresenterRemoved"), apresentadores,
                "saldo de PresenterAdded/Removed = apresentadores da projeção");
        assertEquals(tipos.size(), consultar("SELECT count(*) FROM catalog.event_outbox ob JOIN catalog.event_store es ON "
                + "es.global_position = ob.global_position WHERE es.stream_id = '" + id + "'"), "uma linha de outbox por evento, nenhuma em dobro");
        replay.rebuild(projection, CatalogEventProjection.STREAM_TYPE, SOMBRA);
        assertEquals(List.of(), replay.divergentTables(projection, SOMBRA, COMPARAVEIS));
    }

    /**
     * Updates concorrentes com um {@code DELETE} no meio: o cancelamento é o ÚLTIMO evento do stream, uma vez só; update que perde a
     * corrida vira 404 (evento já apagado) ou termina antes dele. Nenhuma versão depois do {@code EventCancelled}, nenhuma pulada.
     */
    @RepeatedTest(3)
    void deleteConcorrenteComUpdatesCancelaUmaVezEDepoisDeleNadaSeGrava() throws Exception {
        Event base = eventService.createEvent(novo("ES delete concorrente", 50, "Hugo"));
        long id = base.getEvent_id();
        int updates = 5;
        CyclicBarrier largada = new CyclicBarrier(updates + 2);
        ExecutorService pool = Executors.newFixedThreadPool(updates + 2);
        try {
            List<Future<Resultado>> fs = new ArrayList<>();
            for (int i = 0; i < updates + 2; i++) {
                int k = i;
                fs.add(pool.submit(() -> {
                    largada.await();
                    if (k >= updates) { // dois deletes: o segundo é no-op ou 404
                        return tentar(() -> eventService.delete(id));
                    }
                    Event e = novo("ES delete concorrente", 50, k % 2 == 0 ? new String[] {"Hugo", "Ana"} : new String[] {"Bia"});
                    e.setEvent_id(id);
                    e.setDescription("upd " + k);
                    return tentar(() -> eventService.updateEvent(e));
                }));
            }
            for (Future<Resultado> f : fs) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        List<String> tipos = tiposDoStream(id);
        assertEquals(1, contarTipo(id, "EventCancelled"), "cancelado uma vez só");
        assertEquals("EventCancelled", tipos.get(tipos.size() - 1), "nada depois do cancelamento: " + tipos);
        assertEquals(0, consultar("SELECT count(*) FROM catalog.event WHERE event_id = " + id), "o evento sumiu da projeção");
        assertEquals(tipos.size(), consultar("SELECT count(*) FROM catalog.event_outbox ob JOIN catalog.event_store es ON "
                + "es.global_position = ob.global_position WHERE es.stream_id = '" + id + "'"));
    }

    /** O reservation recusa apagar a capacidade de evento com reserva (409): o catalog não appenda nem apaga nada. */
    @Test
    void deleteQueOReservationRecusaNaoGravaEventCancelled() throws Exception {
        Event e = eventService.createEvent(novo("ES com reserva", 4));
        FakeEventCapServer.get().marcarReserva(e.getEvent_id());

        assertThrows(EventoEmConflitoException.class, () -> eventService.delete(e.getEvent_id()));

        assertEquals(List.of("EventCreated"), tiposDoStream(e.getEvent_id()), "nenhum EventCancelled");
        assertEquals(1, consultar("SELECT count(*) FROM catalog.event WHERE event_id = " + e.getEvent_id()));
        eventService.delete(999999L);                              // inexistente: no-op silencioso, sem evento
        assertEquals(1, consultar("SELECT count(*) FROM catalog.event_store"));
    }

    @Test
    void eventUpdatedNaoCarregaCapacidade() throws Exception {
        Event e = eventService.createEvent(novo("ES sem capacidade no update", 8));
        e.setLot_quantity(9);
        e.setDescription("outra");
        eventService.updateEvent(e);

        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT payload::text FROM catalog.event_store WHERE event_type = 'EventCreated'")) {
                assertTrue(rs.next());
                assertEquals(8, json.readTree(rs.getString(1)).get("capacity").asInt(), "EventCreated leva a capacidade inicial");
            }
            try (ResultSet rs = st.executeQuery("SELECT payload::text FROM catalog.event_store WHERE event_type = 'EventUpdated'")) {
                assertTrue(rs.next());
                var p = json.readTree(rs.getString(1));
                assertFalse(p.has("capacity") || p.has("buyTimeLimit"), "capacidade é de reservation, não do stream: " + p);
                assertEquals("outra", p.get("description").asText());
            }
        }
        assertEquals(9, eventService.findById(e.getEvent_id()).getLot_quantity(), "a capacidade mudou no eventcap");
    }

    @Test
    void eventoPreESERecusadoEOBackfillDaMigracaoCriaOStream() throws Exception {
        Event e = eventService.createEvent(novo("ES pre-ES", 6, "Iara"));
        long id = e.getEvent_id();
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) { // estado de uma linha anterior à #28
            st.execute("UPDATE catalog.event SET version = 0 WHERE event_id = " + id);
            st.execute("TRUNCATE TABLE catalog.event_store, catalog.event_outbox");
        }

        EventoEmConflitoException ex = assertThrows(EventoEmConflitoException.class, () -> eventService.updateEvent(e));
        assertTrue(String.valueOf(ex.getMessage()).contains("pré-ES"), ex.getMessage());
        assertThrows(EventoEmConflitoException.class, () -> eventService.delete(id));
        assertEquals(0, consultar("SELECT count(*) FROM catalog.event_store"), "nenhum stream órfão");
        assertEquals(1, consultar("SELECT count(*) FROM catalog.event WHERE event_id = " + id), "e nada foi removido");

        // o backfill da 20261022 age no schema em que event está (catalog, depois do cutover); cwd = o módulo catalog
        String migracao = java.nio.file.Files.readString(
                java.nio.file.Path.of("../db/migrations/20261022-catalog-event-version.sql"));
        for (int i = 0; i < 2; i++) { // idempotente: a 2ª aplicação não duplica nada
            try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
                st.execute(migracao);
            }
        }
        assertEquals(List.of("EventCreated"), tiposDoStream(id));
        assertEquals(1, consultar("SELECT version FROM catalog.event WHERE event_id = " + id));
        assertEquals(1, consultar("SELECT count(*) FROM catalog.event_outbox"));
        assertEquals(0, consultar("SELECT count(*) FROM public.event_store WHERE stream_type = 'catalog.event'"),
                "nada no event store do monólito");

        e.setDescription("depois do backfill");
        eventService.updateEvent(e);
        assertEquals(List.of("EventCreated", "EventUpdated"), tiposDoStream(id));
        replay.rebuild(projection, CatalogEventProjection.STREAM_TYPE, SOMBRA);
        assertEquals(List.of(), replay.divergentTables(projection, SOMBRA, COMPARAVEIS),
                "o EventCreated do backfill reconstrói a mesma linha e os apresentadores");
    }
}
