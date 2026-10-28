package com.gerenciador.eventos.catalog;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.gerenciador.eventos.TestSupport.FakeEventCapServer;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.catalogclient.api.Event;
import com.gerenciador.eventos.catalogclient.api.EventoEmConflitoException;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventReplay;

/**
 * Atomicidade do {@code updateEvent} (issue #60): o {@code updatePolicy} do reservation roda ANTES de qualquer escrita no catalog
 * e metadados + apresentadores entram numa transação só (um command do {@link CatalogEventWriter}). Postgres real, reservation
 * falso por HTTP ({@link FakeEventCapServer}, com o cliente real).
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class EventUpdateAtomicoTest {

    private static final String SOMBRA = "replay_t60";
    private static final Map<String, List<String>> COMPARAVEIS = Map.of("event", List.of(
            "event_id", "creator_id", "event_name", "ead", "address", "event_date", "description", "version"));

    @Autowired private EventService eventService;
    @Autowired private DatabaseConnection db;
    @Autowired private EventReplay replay;
    @MockitoSpyBean private CatalogEventProjection projection;

    @BeforeEach
    void preparar() throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE TABLE catalog.event, catalog.event_store, catalog.event_outbox, "
                    + "catalog.event_snapshot, catalog.projection_checkpoint CASCADE");
        }
        FakeEventCapServer.get().reset();
    }

    @AfterEach
    void limpar() throws Exception {
        reset(projection);
        FakeEventCapServer.get().reset();
        replay.drop(SOMBRA);
    }

    private Event criar(String nome, int capacidade, String... apresentadores) {
        Event e = new Event();
        e.setCreator_id(4242L);
        e.setEvent_name(nome);
        e.setIs_EAD(true);
        e.setEvent_date(LocalDateTime.now().plusDays(30).truncatedTo(ChronoUnit.SECONDS));
        e.setLot_quantity(capacidade);
        e.setDescription("desc " + nome);
        e.setPresenters(new ArrayList<>(List.of(apresentadores)));
        return eventService.createEvent(e);
    }

    /** Tudo o que o catalog guarda do evento: linha viva, apresentadores, stream ("tipo@versão") e outbox. */
    private String estadoDoCatalog(long id) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT event_name, description, version FROM catalog.event WHERE event_id = " + id)) {
                rs.next();
                sb.append(rs.getString(1)).append('|').append(rs.getString(2)).append("|v").append(rs.getInt(3));
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT presenter_name FROM catalog.eventpresenter WHERE event_id = " + id + " ORDER BY presenter_name")) {
                while (rs.next()) {
                    sb.append("|P:").append(rs.getString(1));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT event_type, version FROM catalog.event_store WHERE stream_id = '"
                    + id + "' ORDER BY version")) {
                while (rs.next()) {
                    sb.append("|").append(rs.getString(1)).append('@').append(rs.getInt(2));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM catalog.event_outbox")) {
                rs.next();
                sb.append("|outbox=").append(rs.getInt(1));
            }
        }
        return sb.toString();
    }

    /** Edita nome, descrição, capacidade e troca os apresentadores (Ana sai, Caio entra). */
    private Event edicao(Event base) {
        base.setEvent_name(base.getEvent_name() + " editado");
        base.setDescription("descrição nova");
        base.setLot_quantity(base.getLot_quantity() + 3);
        base.setPresenters(new ArrayList<>(List.of("Bia", "Caio")));
        return base;
    }

    // ===================== reservation primeiro =====================

    /** O reservation recusa (400 capacidade abaixo do reservado; 409): nada vai para o catalog, nem metadados, nem apresentadores. */
    @Test
    void updatePolicyRecusadoNaoGravaNadaNoCatalog() throws Exception {
        Event e = criar("AT recusa", 5, "Ana", "Bia");
        long id = e.getEvent_id();
        String antes = estadoDoCatalog(id);
        FakeEventCapServer reservation = FakeEventCapServer.get();

        reservation.recusarPolitica(400);
        assertThrows(IllegalArgumentException.class, () -> eventService.updateEvent(edicao(e)));
        assertEquals(antes, estadoDoCatalog(id), "400: o catalog ficou intacto");

        reservation.recusarPolitica(409);
        assertThrows(EventoEmConflitoException.class, () -> eventService.updateEvent(edicao(e)));
        assertEquals(antes, estadoDoCatalog(id), "409: o catalog ficou intacto (e vira 409, o status do conflito)");

        reservation.recusarPolitica(503);
        assertThrows(RuntimeException.class, () -> eventService.updateEvent(edicao(e)));
        assertEquals(antes, estadoDoCatalog(id), "reservation fora (5xx): o catalog ficou intacto");
        assertEquals(5, reservation.capacidade(id).getCapacity(), "e o reservation também não mudou");
    }

    /** Evento sem eventcap + a criação pelo PUT também falha: a edição inteira falha sem gravar nada. */
    @Test
    void fallbackDeCriarOEventcapQueFalhaNaoGravaNadaNoCatalog() throws Exception {
        FakeEventCapServer reservation = FakeEventCapServer.get();
        reservation.falharCriacao(true);
        Event e = criar("AT sem eventcap", 5, "Ana", "Bia");
        long id = e.getEvent_id();
        String antes = estadoDoCatalog(id);

        assertThrows(RuntimeException.class, () -> eventService.updateEvent(edicao(e)));

        assertEquals(antes, estadoDoCatalog(id));
    }

    /** O caso "evento sem eventcap" segue funcionando: PATCH 404 -> PUT idempotente com os valores novos, e o catalog grava. */
    @Test
    void eventoSemEventcapCriaOEventcapEDepoisGravaNoCatalog() throws Exception {
        FakeEventCapServer reservation = FakeEventCapServer.get();
        reservation.falharCriacao(true);
        Event e = criar("AT cria no update", 5, "Ana", "Bia");
        reservation.falharCriacao(false);
        reservation.limparChamadas();

        eventService.updateEvent(edicao(e));

        assertEquals(8, reservation.capacidade(e.getEvent_id()).getCapacity());
        assertEquals(List.of("PATCH /internal/eventcaps/" + e.getEvent_id() + "/policy",
                "PUT /internal/eventcaps/" + e.getEvent_id(), "GET /internal/eventcaps/" + e.getEvent_id()),
                reservation.chamadas());
        assertEquals("AT cria no update editado|descrição nova|v4|P:Bia|P:Caio|EventCreated@1|EventUpdated@2|PresenterRemoved@3"
                + "|PresenterAdded@4|outbox=4", estadoDoCatalog(e.getEvent_id()));
    }

    // ===================== uma transação no catalog =====================

    /** Sucesso: EventUpdated + PresenterRemoved + PresenterAdded em versões contíguas; o replay do agregado == a projeção viva. */
    @Test
    void metadadosEApresentadoresEntramComVersoesContiguasEOReplayBate() throws Exception {
        Event e = criar("AT sucesso", 5, "Ana", "Bia");

        Event atualizado = eventService.updateEvent(edicao(e));

        assertEquals("AT sucesso editado|descrição nova|v4|P:Bia|P:Caio|EventCreated@1|EventUpdated@2|PresenterRemoved@3"
                + "|PresenterAdded@4|outbox=4", estadoDoCatalog(e.getEvent_id()));
        assertEquals(List.of("Bia", "Caio"), atualizado.getPresenters().stream().sorted().toList());
        assertEquals(8, atualizado.getLot_quantity());

        replay.rebuild(projection, CatalogEventProjection.STREAM_TYPE, SOMBRA);
        assertEquals(List.of(), replay.divergentTables(projection, SOMBRA, COMPARAVEIS));
    }

    /**
     * Falha na transação do catalog no meio da troca de apresentadores (o PresenterRemoved já foi aplicado): NADA dos dois fica
     * gravado. A janela documentada: o reservation já tinha atualizado a capacidade.
     */
    @Test
    void falhaNaTransacaoDoCatalogNaoDeixaMetadadosNemApresentadoresPelaMetade() throws Exception {
        Event e = criar("AT falha tx", 5, "Ana", "Bia");
        long id = e.getEvent_id();
        String antes = estadoDoCatalog(id);
        doThrow(new SQLException("falha injetada")).when(projection)
                .apply(any(), argThat(ev -> ev != null && "PresenterAdded".equals(ev.eventType())), any());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> eventService.updateEvent(edicao(e)));

        assertEquals("falha injetada", ex.getCause().getMessage());
        assertEquals(antes, estadoDoCatalog(id), "EventUpdated e PresenterRemoved foram desfeitos junto");
        assertEquals(8, FakeEventCapServer.get().capacidade(id).getCapacity(),
                "janela documentada: o reservation já tinha a capacidade nova");

        reset(projection); // a edição repetida passa e o replay continua igual à viva
        eventService.updateEvent(edicao(e));
        replay.rebuild(projection, CatalogEventProjection.STREAM_TYPE, SOMBRA);
        assertEquals(List.of(), replay.divergentTables(projection, SOMBRA, COMPARAVEIS));
    }
}
