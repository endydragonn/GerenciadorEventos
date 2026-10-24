package com.gerenciador.eventos;

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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.TestSupport.FakeReservationServer;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.catalog.CatalogEventProjection;
import com.gerenciador.eventos.catalog.CatalogEventWriter;
import com.gerenciador.eventos.catalog.EventService;
import com.gerenciador.eventos.catalog.api.Event;
import com.gerenciador.eventos.identity.UserService;
import com.gerenciador.eventos.identity.api.User;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.reservationclient.api.EventWalletApi;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventReplay;
import com.gerenciador.eventos.shared.events.EventStore;
import com.gerenciador.eventos.shared.events.NovoEvento;

/**
 * CQRS + Event Sourcing do módulo {@code catalog} (issue #28): cada escrita appenda no stream {@code catalog.event} na
 * transação da projeção inline ({@code event}/{@code eventpresenter}); conflito de versão repete o command; o replay
 * do {@code event_store} na sombra == projeção viva nas colunas comparáveis (sem {@code image_data} nem carimbos).
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CatalogEventSourcingTest {

    private static final String SOMBRA = "replay_t28";
    /** Colunas que o log determina (image_data é blob fora do ES; created_at/updated_at são carimbos do banco). */
    private static final Map<String, List<String>> COMPARAVEIS = Map.of("event", List.of(
            "event_id", "creator_id", "event_name", "ead", "address", "event_date", "description", "version"));

    @Autowired private UserService userService;
    @Autowired private EventService eventService;
    @Autowired private EventWalletApi reservation;
    @Autowired private DatabaseConnection db;
    @Autowired private EventStore eventStore;
    @Autowired private EventReplay replay;
    @Autowired private CatalogEventProjection projection;
    @Autowired private CatalogEventWriter writer;
    @Autowired private ObjectMapper json;

    private long creatorId;
    private String creatorEmail;

    @BeforeEach
    void preparar() throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE TABLE public.users, public.event, public.event_store, public.event_outbox, "
                    + "public.event_snapshot, public.projection_checkpoint CASCADE");
        }
        User u = new User();
        creatorEmail = "criador-" + UUID.randomUUID() + "@example.com";
        u.setName("Criador ES");
        u.setEmail(creatorEmail);
        u.setFone("11" + (900000000 + (int) (Math.random() * 99999999)));
        creatorId = userService.createUser(u).getId();
    }

    @AfterEach
    void derrubarSombra() throws Exception {
        replay.drop(SOMBRA);
    }

    private Event novo(String nome, int capacidade, String... apresentadores) {
        Event e = new Event();
        e.setCreator_id(creatorId);
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
             PreparedStatement ps = c.prepareStatement("SELECT version, event_type FROM public.event_store "
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
        e2.setImage_data(new byte[] {1, 2, 3});                    // imagem: blob fora do ES
        eventService.updateEvent(e2);
        atualizar(e3, "ilimitado editado", "Dani", "Fábio");
        eventService.delete(e4.getEvent_id());                     // EventCancelled

        assertEquals(List.of("EventCreated", "EventUpdated", "PresenterRemoved", "PresenterAdded", "EventUpdated"),
                tiposDoStream(e1.getEvent_id()));
        assertEquals(List.of("EventCreated", "EventUpdated"), tiposDoStream(e2.getEvent_id()));
        assertEquals(List.of("EventCreated", "EventUpdated", "PresenterAdded"), tiposDoStream(e3.getEvent_id()));
        assertEquals(List.of("EventCreated", "EventCancelled"), tiposDoStream(e4.getEvent_id()));
        assertEquals(5, consultar("SELECT version FROM public.event WHERE event_id = " + e1.getEvent_id()),
                "a versão da projeção é a do último evento do stream");
        assertEquals(0, consultar("SELECT count(*) FROM public.event WHERE event_id = " + e4.getEvent_id()),
                "cancelado = linha removida (mesma remoção de antes)");
        assertNull(FakeReservationServer.get().capacidade(e4.getEvent_id()),
                "o eventcap do cancelado foi removido do serviço reservation (chamada síncrona, sem CASCADE)");

        replay.rebuild(projection, CatalogEventProjection.STREAM_TYPE, SOMBRA);

        assertEquals(List.of(), replay.divergentTables(projection, SOMBRA, COMPARAVEIS),
                "event/eventpresenter reconstruídas do event_store == vivas (colunas comparáveis)");
        assertEquals(3, consultar("SELECT count(*) FROM " + SOMBRA + ".event"));
        assertEquals(1, consultar("SELECT count(*) FROM public.event WHERE image_data IS NOT NULL"),
                "a imagem vive só na tabela viva");
        assertEquals(0, consultar("SELECT count(*) FROM " + SOMBRA + ".event WHERE image_data IS NOT NULL"),
                "e não vem do log");

        // a comparação detecta divergência de verdade
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE public.event SET event_name = event_name || ' x' WHERE event_id = " + e2.getEvent_id());
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
             ResultSet rs = st.executeQuery("SELECT payload::text, partition_key, stream_id FROM public.event_store")) {
            assertTrue(rs.next());
            String payload = rs.getString(1);
            assertEquals(String.valueOf(salvo.getEvent_id()), rs.getString(2), "chave da mensagem = id do evento");
            assertEquals(String.valueOf(salvo.getEvent_id()), rs.getString(3));
            assertFalse(payload.contains(creatorEmail) || payload.contains("@"), "sem e-mail/PII: " + payload);
            assertFalse(payload.toLowerCase().contains("image"), "image_data fica fora do ES: " + payload);
            var p = json.readTree(payload);
            assertEquals(creatorId, p.get("creatorId").asLong(), "só o id do criador");
            assertEquals(7, p.get("capacity").asInt());
            assertEquals(salvo.getEvent_date().toString(), p.get("buyTimeLimit").asText(), "valor calculado no command");
            assertEquals("Gil", p.get("presenters").get(0).asText());
            assertFalse(rs.next());
        }
        assertEquals(1, consultar("SELECT count(*) FROM public.event_outbox"), "outbox na mesma transação do append");
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
                    st.execute("DELETE FROM public.event_store WHERE stream_id = '" + id + "' AND version = 2");
                }
            }
            tx.append("PresenterAdded", json.createObjectNode().put("eventId", id).put("presenterName", "Zé"));
            return null;
        });
        assertEquals(2, tentativas.get(), "conflito -> rollback e o command roda de novo");
        assertEquals(List.of("EventCreated", "PresenterAdded"), tiposDoStream(id));
        assertEquals(2, consultar("SELECT version FROM public.event WHERE event_id = " + id));

        // 2) conflito permanente: a v3 existe no log mas não na projeção -> esgota as tentativas, nada é gravado
        appendPorFora(id, 3);
        long antes = consultar("SELECT count(*) FROM public.event_store");
        e.setDescription("não pode gravar");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> eventService.updateEvent(e));
        assertTrue(ex.getMessage().contains("Conflito de versão"), ex.getMessage());
        assertEquals(antes, consultar("SELECT count(*) FROM public.event_store"));
        assertEquals("desc ES conflito", eventService.findById(id).getDescription());
        assertEquals(2, consultar("SELECT version FROM public.event WHERE event_id = " + id));
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
        assertEquals(1 + n, consultar("SELECT version FROM public.event WHERE event_id = " + base.getEvent_id()));

        replay.rebuild(projection, CatalogEventProjection.STREAM_TYPE, SOMBRA);
        assertEquals(List.of(), replay.divergentTables(projection, SOMBRA, COMPARAVEIS));
    }

    /**
     * O antigo {@code ON DELETE RESTRICT} de {@code walletevent}: as tabelas ficaram em schemas diferentes (sem FK), então
     * quem recusa apagar o evento com reserva é o serviço reservation (409), chamado como ÚLTIMO passo da transação do
     * catalog, antes do commit.
     */
    @Test
    void deleteQueOReservationRecusaDesfazOEventCancelled() throws Exception {
        Event e = eventService.createEvent(novo("ES com reserva", 4));
        reservation.addLink(new EventWallet(creatorId, e.getEvent_id()), null, false);

        assertThrows(RuntimeException.class, () -> eventService.delete(e.getEvent_id()));

        assertEquals(List.of("EventCreated"), tiposDoStream(e.getEvent_id()), "o append foi no rollback junto com o DELETE");
        assertEquals(1, consultar("SELECT count(*) FROM public.event WHERE event_id = " + e.getEvent_id()));
        eventService.delete(999999L);                              // inexistente: no-op silencioso, sem evento
        assertEquals(1, consultar("SELECT count(*) FROM public.event_store"));
    }

    @Test
    void eventUpdatedNaoCarregaCapacidade() throws Exception {
        Event e = eventService.createEvent(novo("ES sem capacidade no update", 8));
        e.setLot_quantity(9);
        e.setDescription("outra");
        eventService.updateEvent(e);

        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT payload::text FROM public.event_store WHERE event_type = 'EventCreated'")) {
                assertTrue(rs.next());
                assertEquals(8, json.readTree(rs.getString(1)).get("capacity").asInt(), "EventCreated leva a capacidade inicial");
            }
            try (ResultSet rs = st.executeQuery("SELECT payload::text FROM public.event_store WHERE event_type = 'EventUpdated'")) {
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
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) { // volta ao estado anterior à #28
            st.execute("UPDATE public.event SET version = 0 WHERE event_id = " + id);
            st.execute("TRUNCATE TABLE public.event_store, public.event_outbox");
        }

        RuntimeException ex = assertThrows(RuntimeException.class, () -> eventService.updateEvent(e));
        assertTrue(String.valueOf(ex.getMessage()).contains("pré-ES"), ex.getMessage());
        assertThrows(RuntimeException.class, () -> eventService.delete(id));
        assertEquals(0, consultar("SELECT count(*) FROM public.event_store"), "nenhum stream órfão");
        assertEquals(1, consultar("SELECT count(*) FROM public.event WHERE event_id = " + id), "e nada foi removido");

        // cwd = o módulo monolito (multi-módulo, #32): o db/ fica um nível acima
        String migracao = java.nio.file.Files.readString(
                java.nio.file.Path.of("../db/migrations/20261022-catalog-event-version.sql"));
        for (int i = 0; i < 2; i++) { // idempotente: a 2ª aplicação não duplica nada
            try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
                st.execute(migracao);
            }
        }
        assertEquals(List.of("EventCreated"), tiposDoStream(id));
        assertEquals(1, consultar("SELECT version FROM public.event WHERE event_id = " + id));
        assertEquals(1, consultar("SELECT count(*) FROM public.event_outbox"));

        e.setDescription("depois do backfill");
        eventService.updateEvent(e);
        assertEquals(List.of("EventCreated", "EventUpdated"), tiposDoStream(id));
        replay.rebuild(projection, CatalogEventProjection.STREAM_TYPE, SOMBRA);
        assertEquals(List.of(), replay.divergentTables(projection, SOMBRA, COMPARAVEIS),
                "o EventCreated do backfill reconstrói a mesma linha e os apresentadores");
    }
}
