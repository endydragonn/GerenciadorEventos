package com.gerenciador.eventos.catalog;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;

import com.gerenciador.eventos.TestSupport.FakeEventCapServer;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.catalogclient.CatalogClient;
import com.gerenciador.eventos.catalogclient.api.CatalogUnavailableException;
import com.gerenciador.eventos.catalogclient.api.Event;
import com.gerenciador.eventos.catalogclient.api.EventoEmConflitoException;
import com.gerenciador.eventos.catalogclient.api.EventoNaoEncontradoException;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * PONTA A PONTA do salto {@code gateway -> catalog} (issue #35): o {@link CatalogClient} REAL (o que o gateway vai usar) fala por
 * HTTP com o serviço REAL, na porta sorteada, contra o Postgres real (schema {@code catalog}); o reservation é o servidor falso. É
 * o teste que mantém o contrato da API interna honesto dos dois lados: DTO {@link Event} em JSON, imagem em bytes, status HTTP
 * (401 token, 400 regra de negócio e nome duplicado, 404, 409 só para conflito, 500 genérico) e o mapeamento de erro do cliente.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class CatalogApiE2ETest {

    @LocalServerPort private int porta;
    @Value("${internal.api.token}") private String tokenInterno;
    @Autowired private DatabaseConnection db;
    @MockitoSpyBean private EventRepository eventRepository;

    private CatalogClient cliente;

    @BeforeAll
    void abrirCliente() {
        cliente = new CatalogClient("http://localhost:" + porta, tokenInterno, 50, Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofSeconds(15), Duration.ofSeconds(20));
    }

    @AfterAll
    void fecharCliente() throws Exception {
        cliente.close();
    }

    private static Event novo(String nome, int capacidade) {
        Event e = new Event();
        e.setCreator_id(77L);
        e.setEvent_name(nome + " " + System.nanoTime());
        e.setIs_EAD(false);
        e.setAddress("Rua A, 1");
        e.setEvent_date(LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS));
        e.setLot_quantity(capacidade);
        e.setDescription("desc");
        e.setPresenters(new ArrayList<>(List.of("Zeca", "Ana")));
        return e;
    }

    // ===================== CRUD pelo cliente real =====================

    @Test
    void crudPeloHttpPreservaOContratoDoEvent() {
        Event criado = cliente.createEvent(novo("E2E crud", 5));
        assertTrue(criado.getEvent_id() != null && criado.getEvent_id() > 0);
        assertEquals(5, criado.getLot_quantity(), "capacidade hidratada do reservation");
        assertEquals(5, criado.getQuantity());
        assertEquals(criado.getEvent_date(), criado.getBuy_time_limit(), "buy_time_limit default = data do evento");

        Event lido = cliente.findById(criado.getEvent_id());
        assertEquals(criado.getEvent_name(), lido.getEvent_name());
        assertEquals(Boolean.FALSE, lido.getIs_EAD(), "is_EAD atravessa o JSON");
        assertEquals("Rua A, 1", lido.getAddress());
        assertEquals(77L, lido.getCreator_id());
        assertEquals(List.of("Ana", "Zeca"), lido.getPresenters());
        assertNull(lido.getCreator_name(), "creator_name é composto no gateway, não no catalog");

        lido.setDescription("editado");
        lido.setLot_quantity(8);
        lido.setPresenters(List.of("Bia"));
        Event editado = cliente.updateEvent(lido);
        assertEquals("editado", editado.getDescription());
        assertEquals(8, editado.getLot_quantity());
        assertEquals(List.of("Bia"), cliente.findById(criado.getEvent_id()).getPresenters());

        assertEquals(List.of(criado.getEvent_id()),
                cliente.searchEvents(criado.getEvent_name()).stream().map(Event::getEvent_id).toList());
        assertTrue(cliente.findByCreatorId(77L).stream().anyMatch(x -> x.getEvent_id().equals(criado.getEvent_id())));
        assertEquals(List.of(), cliente.findByCreatorId(null));
        assertEquals("", cliente.getValidationErrors(novo("E2E valido", 3)), "válido = texto vazio");
        assertTrue(cliente.getValidationErrors(new Event()).contains("Nome do evento é obrigatório"));

        cliente.delete(criado.getEvent_id());
        assertThrows(EventoNaoEncontradoException.class, () -> cliente.findById(criado.getEvent_id()));
        assertNull(FakeEventCapServer.get().capacidade(criado.getEvent_id()), "eventcap removido no reservation");
        cliente.delete(criado.getEvent_id()); // inexistente: no-op (o 404 do DELETE é do gateway)
    }

    /** O termo da busca vai como VALOR codificado, não como template: {@code +} não vira espaço e {@code {x}} não é variável. */
    @Test
    void buscaComCaracteresEspeciaisNoTermoAchaOEvento() {
        Event e = novo("C++ & Show {x} 100%", 2);
        Event criado = cliente.createEvent(e);

        assertEquals(List.of(criado.getEvent_id()),
                cliente.searchEvents(criado.getEvent_name()).stream().map(Event::getEvent_id).toList());
        assertTrue(cliente.searchEvents("C++ & Show {x}").stream().anyMatch(x -> x.getEvent_id().equals(criado.getEvent_id())));
    }

    @Test
    void imagemTrafegaEmBytesEFicaForaDasListas() {
        Event e = cliente.createEvent(novo("E2E imagem", 2));
        byte[] imagem = {(byte) 0xFF, (byte) 0xD8, 0, 1, 2, 127, (byte) 0x80};
        assertNull(cliente.findImage(e.getEvent_id()), "sem imagem: 404 vira null");

        cliente.saveImage(e.getEvent_id(), imagem);

        assertArrayEquals(imagem, cliente.findImage(e.getEvent_id()));
        assertArrayEquals(imagem, cliente.findById(e.getEvent_id()).getImage_data(), "findById traz a imagem");
        assertNull(cliente.searchEvents(e.getEvent_name()).get(0).getImage_data(), "busca sem image_data");
        assertNull(cliente.findByCreatorId(77L).stream().filter(x -> x.getEvent_id().equals(e.getEvent_id())).findFirst()
                .orElseThrow().getImage_data(), "lista do criador sem image_data");
        assertThrows(IllegalArgumentException.class, () -> cliente.saveImage(e.getEvent_id(), null), "null não apaga: 400");
        assertArrayEquals(imagem, cliente.findImage(e.getEvent_id()), "a imagem continua lá");
        assertThrows(EventoNaoEncontradoException.class, () -> cliente.saveImage(999_999L, imagem));
        assertNull(cliente.findImage(999_999L));
    }

    // ===================== endpoints leves =====================

    @Test
    void existsECreatorSaoLevesESem404Falso() {
        Event e = cliente.createEvent(novo("E2E leve", 2));
        clearInvocations(eventRepository);

        assertTrue(cliente.exists(e.getEvent_id()));
        assertFalse(cliente.exists(999_999L), "inexistente = false (200), não erro");
        assertEquals(77L, cliente.findCreatorId(e.getEvent_id()));
        assertNull(cliente.findCreatorId(999_999L), "inexistente = null (404)");

        verify(eventRepository, never()).findById(anyLong());
    }

    // ===================== erros: 404, 400, 409 =====================

    @Test
    void inexistenteDa404TipadoNoCliente() {
        EventoNaoEncontradoException ex = assertThrows(EventoNaoEncontradoException.class, () -> cliente.findById(999_999L));
        assertTrue(ex.getMessage().startsWith("Evento não encontrado"), ex.getMessage());
        Event fantasma = novo("E2E fantasma", 2);
        fantasma.setEvent_id(999_999L);
        assertThrows(EventoNaoEncontradoException.class, () -> cliente.updateEvent(fantasma));
    }

    @Test
    void regraDeNegocioENomeDuplicadoDao400() {
        Event semCapacidade = novo("E2E sem capacidade", 1);
        semCapacidade.setLot_quantity(null);
        assertThrows(IllegalArgumentException.class, () -> cliente.createEvent(semCapacidade));

        Event e = cliente.createEvent(novo("E2E dup", 2));
        Event mesmoNome = novo("x", 2);
        mesmoNome.setEvent_name(e.getEvent_name());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> cliente.createEvent(mesmoNome));
        assertTrue(ex.getMessage().contains("já existente"), ex.getMessage());
    }

    /**
     * Creates concorrentes com o MESMO nome passam juntos pela pré-checagem ({@code nameExists}); o perdedor bate no
     * {@code UNIQUE (event_name)} (23505) e tem de virar 400, nunca 500 (que o gateway trataria como catalog fora).
     */
    @Test
    void createsConcorrentesComOMesmoNomeDaoUm201EOResto400() throws Exception {
        String nome = "E2E corrida " + System.nanoTime();
        int n = 8;
        CyclicBarrier largada = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<String>> fs = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                fs.add(pool.submit(() -> {
                    Event e = novo("x", 2);
                    e.setEvent_name(nome);
                    largada.await();
                    try {
                        cliente.createEvent(e);
                        return "201";
                    } catch (IllegalArgumentException ex) {
                        return "400";
                    } catch (RuntimeException ex) {
                        return "outro: " + ex;
                    }
                }));
            }
            List<String> resultados = new ArrayList<>();
            for (Future<String> f : fs) {
                resultados.add(f.get());
            }
            assertEquals(1, resultados.stream().filter("201"::equals).count(), resultados.toString());
            assertEquals(n - 1, resultados.stream().filter("400"::equals).count(), resultados.toString());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Prova determinística do mapeamento do 23505: com a pré-checagem ({@code nameExists}) neutralizada, o create duplicado chega
     * ao INSERT, bate no {@code Event_event_name_key} e ainda assim responde 400 "já existente" (não 500).
     */
    @Test
    void violacaoDoUniqueDoNomeNoBancoDa400() {
        Event e = cliente.createEvent(novo("E2E 23505", 2));
        Event mesmoNome = novo("x", 2);
        mesmoNome.setEvent_name(e.getEvent_name());
        doReturn(false).when(eventRepository).nameExists(anyString());
        try {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> cliente.createEvent(mesmoNome));
            assertEquals("Nome de evento já existente", ex.getMessage());
        } finally {
            reset(eventRepository);
        }
    }

    /** Outro 23505 (aqui o {@code Event_pkey}: a linha com o próximo id da sequence já existe) NÃO é "nome já existente": 500. */
    @Test
    void violacaoDeOutraUniqueNaoViraNomeDuplicado() throws Exception {
        String ocupada = "E2E pkey ocupada " + System.nanoTime();
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO catalog.event (event_id, creator_id, event_name, ead, event_date, description, version) "
                    + "OVERRIDING SYSTEM VALUE SELECT CASE WHEN is_called THEN last_value + 1 ELSE last_value END, 1, '" + ocupada + "', true, "
                    + "CURRENT_TIMESTAMP + interval '10 days', 'desc', 1 "
                    + "FROM catalog.event_event_id_seq");
        }
        try {
            CatalogUnavailableException ex = assertThrows(CatalogUnavailableException.class,
                    () -> cliente.createEvent(novo("E2E pkey", 2)));
            assertTrue(ex.getMessage().contains("HTTP 500"), ex.getMessage());
            assertFalse(ex.getMessage().contains("Event_pkey"), "o corpo do 500 não repete a mensagem do SQL: " + ex.getMessage());
        } finally { // a linha crua não passou pelo serviço (sem stream nem eventcap): não pode sobrar no banco
            try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
                st.execute("DELETE FROM catalog.event WHERE event_name = '" + ocupada + "'");
            }
        }
    }

    /**
     * Só o conflito do catálogo é 409 ({@link EventoEmConflitoException}); outra {@link IllegalStateException} (ex.: pool do
     * cliente HTTP fechado, buraco no stream) é falha interna: 500, com corpo genérico (o detalhe fica no log do serviço).
     */
    @Test
    void illegalStateQueNaoEConflitoDa500ComCorpoGenerico() {
        doThrow(new IllegalStateException("Connection pool shut down: detalhe-interno"))
                .when(eventRepository).findCreatorId(anyLong());
        try {
            CatalogUnavailableException ex = assertThrows(CatalogUnavailableException.class, () -> cliente.findCreatorId(1L));
            assertTrue(ex.getMessage().contains("HTTP 500"), ex.getMessage());
            assertFalse(ex.getMessage().contains("detalhe-interno"), "corpo do 500 genérico: " + ex.getMessage());
        } finally {
            reset(eventRepository);
        }
    }

    @Test
    void apagarEventoComReservaDa409EOEventoFica() {
        Event e = cliente.createEvent(novo("E2E com reserva", 2));
        FakeEventCapServer.get().marcarReserva(e.getEvent_id());

        assertThrows(EventoEmConflitoException.class, () -> cliente.delete(e.getEvent_id()));
        assertTrue(cliente.exists(e.getEvent_id()));
    }

    @Test
    void eventoPreESDa409() throws Exception {
        Event e = cliente.createEvent(novo("E2E pre-ES", 2));
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE catalog.event SET version = 0 WHERE event_id = " + e.getEvent_id());
        }
        EventoEmConflitoException ex = assertThrows(EventoEmConflitoException.class, () -> cliente.updateEvent(e));
        assertTrue(ex.getMessage().contains("pré-ES"), ex.getMessage());
    }

    // ===================== token de serviço =====================

    @Test
    void semTokenOuComTokenErradoDa401ENaoGrava() {
        RestClient cru = RestClient.builder().baseUrl("http://localhost:" + porta).build();
        for (String rota : List.of("/internal/events/1", "/internal/events/1/exists", "/internal/events/search?term=x",
                "/internal/events/1/image", "/internal/events/1/creator", "/internal/events;x=1/1", "/")) {
            int semToken = cru.get().uri(rota).exchange((req, resp) -> resp.getStatusCode().value());
            int errado = cru.get().uri(rota).header(CatalogClient.HEADER_TOKEN_INTERNO, tokenInterno + "x")
                    .exchange((req, resp) -> resp.getStatusCode().value());
            assertEquals(401, semToken, rota);
            assertEquals(401, errado, rota);
        }
        String nome = "E2E sem token " + System.nanoTime();
        Event e = novo("x", 2);
        e.setEvent_name(nome);
        int post = cru.post().uri("/internal/events").contentType(MediaType.APPLICATION_JSON).body(e)
                .exchange((req, resp) -> resp.getStatusCode().value());
        assertEquals(401, post);
        assertEquals(List.of(), cliente.searchEvents(nome), "o POST sem token não criou o evento");
    }

    @Test
    void semTokenConfiguradoServicoEClienteNaoSobem() {
        assertThrows(IllegalStateException.class, () -> new InternalTokenFilter(""));
        assertThrows(IllegalStateException.class, () -> new CatalogClient("http://localhost:" + porta, " ", 1,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }
}
