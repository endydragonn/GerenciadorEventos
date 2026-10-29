package com.gerenciador.eventos.stock;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.reservationclient.api.EventCap;
import com.gerenciador.eventos.stockclient.StockClient;
import com.gerenciador.eventos.stockclient.api.HoldRequest;

/**
 * Contrato HTTP da API interna do stock (issue #47): token de serviço, pedido malformado e o {@code /internal/eventcaps/**} que veio do
 * reservation (mesmos verbos e códigos), agora contra o ledger de holds (delete barrado por hold de qualquer status). O ledger em si
 * (idempotência, lápide, overselling, S7) é do {@link StockHoldTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class StockApiE2ETest extends StockTestSupport {

    @LocalServerPort private int porta;
    @Value("${internal.api.token}") private String tokenInterno;

    private StockClient cliente;
    private RestClient comToken;
    private RestClient semToken;

    @BeforeAll
    void abrirClientes() {
        cliente = new StockClient("http://localhost:" + porta, tokenInterno, 10, Duration.ofSeconds(2),
                Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30));
        comToken = RestClient.builder().baseUrl("http://localhost:" + porta)
                .defaultHeader(StockClient.HEADER_TOKEN_INTERNO, tokenInterno).build();
        semToken = RestClient.builder().baseUrl("http://localhost:" + porta).build();
    }

    @AfterAll
    void fecharCliente() throws Exception {
        cliente.close();
    }

    private static int status(RestClient.RequestHeadersSpec<?> req) {
        return req.exchange((rq, resp) -> resp.getStatusCode().value());
    }

    // ===================== token de serviço =====================

    @Test
    public void semTokenOuComTokenErradoTodaRotaInternaDa401() throws Exception {
        String[] rotas = {"/internal/eventcaps/1", "/internal/holds", "/internal/holds/" + UUID.randomUUID() + "/release"};
        for (String rota : rotas) {
            assertEquals(401, status(semToken.get().uri(rota)), rota);
            assertEquals(401, status(semToken.post().uri(rota).contentType(MediaType.APPLICATION_JSON).body("{}")), rota);
            assertEquals(401, status(semToken.get().uri(rota).header(StockClient.HEADER_TOKEN_INTERNO, tokenInterno + "x")), rota);
            assertEquals(401, status(semToken.get().uri(rota).header(StockClient.HEADER_TOKEN_INTERNO, "")), rota);
        }
        long id = novoId();
        assertEquals(401, status(semToken.put().uri("/internal/eventcaps/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .body("{\"capacity\":5}")));
        assertNull(eventCapRepository.findByEventId(id), "o PUT sem token não criou o eventcap");
        long semHold = novoId();
        assertEquals(401, status(semToken.post().uri("/internal/holds").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("holdId", UUID.randomUUID().toString(), "userId", 1, "eventId", semHold, "mode", "cas"))));
        assertEquals(0, contarHolds(semHold, "held", null));
    }

    /** O filtro não pode decidir por prefixo do URI cru: {@code /internal;x=1/...} e {@code /%69nternal/...} são normalizados pelo MVC. */
    @Test
    public void caminhosDisfarcadosEForaDeInternalTambemDao401SemToken() {
        URI base = URI.create("http://localhost:" + porta);
        assertEquals(401, status(semToken.get().uri(base.resolve("/internal;x=1/eventcaps/1"))));
        assertEquals(401, status(semToken.post().uri(base.resolve("/%69nternal/holds")).contentType(MediaType.APPLICATION_JSON).body("{}")));
        assertEquals(401, status(semToken.get().uri(base.resolve("/qualquer/coisa"))));
        assertEquals(401, status(semToken.get().uri(base.resolve("/"))));
    }

    @Test
    public void comTokenCertoOFluxoEhNormalEOClienteComTokenErradoLevaOHttp401() throws Exception {
        assertEquals(404, status(comToken.get().uri("/internal/eventcaps/{id}", novoId())));
        StockClient errado = new StockClient("http://localhost:" + porta, "outro-token", 2, Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(20));
        try {
            RuntimeException ex = assertThrows(RuntimeException.class, () -> errado.release(UUID.randomUUID(), 1L, 1L));
            assertTrue(ex.getMessage().contains("401"), ex.getMessage());
        } finally {
            errado.close();
        }
    }

    /** Sem a variável o serviço (e o cliente) FALHAM NO BOOT com mensagem clara: sem default, sem subir aberto. */
    @Test
    public void semTokenConfiguradoServicoEClienteNaoSobem() {
        IllegalStateException servico = assertThrows(IllegalStateException.class, () -> new InternalTokenFilter(""));
        assertTrue(servico.getMessage().contains("INTERNAL_API_TOKEN"), servico.getMessage());
        assertThrows(IllegalStateException.class, () -> new InternalTokenFilter("   "));
        IllegalStateException clienteSemToken = assertThrows(IllegalStateException.class, () -> new StockClient(
                "http://localhost:1", "", 2, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertTrue(clienteSemToken.getMessage().contains("INTERNAL_API_TOKEN"), clienteSemToken.getMessage());
    }

    // ===================== pedido malformado = 400 =====================

    @Test
    public void pedidoMalformadoEhErroDoChamador400() {
        assertEquals(400, status(comToken.post().uri("/internal/holds").contentType(MediaType.APPLICATION_JSON).body("{nao e json")));
        assertEquals(400, status(comToken.post().uri("/internal/holds/{id}/release", "nao-e-uuid")
                .contentType(MediaType.APPLICATION_JSON).body("{\"eventId\":1,\"userId\":1}")));
        assertEquals(400, status(comToken.post().uri("/internal/holds").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("holdId", "nao-e-uuid", "userId", 1, "eventId", 1, "mode", "cas"))));
        assertEquals(400, status(comToken.get().uri("/internal/eventcaps/abc")));
        assertEquals(400, status(comToken.post().uri("/internal/holds").contentType(MediaType.APPLICATION_JSON)), "sem corpo");
        // campos que faltam / modo desconhecido: 400 com a mensagem
        IllegalArgumentException semModo = assertThrows(IllegalArgumentException.class,
                () -> cliente.hold(new HoldRequest(UUID.randomUUID(), 1L, 1L, "xpto")));
        assertEquals("mode deve ser cas ou naive", semModo.getMessage());
        assertThrows(IllegalArgumentException.class, () -> cliente.hold(new HoldRequest(UUID.randomUUID(), null, 1L, "cas")));
        assertThrows(IllegalArgumentException.class, () -> cliente.hold(new HoldRequest(null, 1L, 1L, "cas")));
    }

    // ===================== /internal/eventcaps (veio do reservation) =====================

    @Test
    public void eventcapCriacaoEIdempotenteEPoliticaComEventoSemEventcapDa404() {
        long id = novoId();
        int criou = status(comToken.put().uri("/internal/eventcaps/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("capacity", 6)));
        int repetiu = status(comToken.put().uri("/internal/eventcaps/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("capacity", 50)));
        assertEquals(201, criou);
        assertEquals(200, repetiu, "já existia: não altera nada");
        EventCap cap = comToken.get().uri("/internal/eventcaps/{id}", id).retrieve().body(EventCap.class);
        assertEquals(6, cap.getCapacity());
        assertEquals(6, cap.getQuant());
        assertEquals(400, status(comToken.put().uri("/internal/eventcaps/{id}", novoId()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("capacity", 0))), "capacity 0 é recusada");

        assertEquals(404, status(comToken.patch().uri("/internal/eventcaps/{id}/policy", novoId())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("capacity", 5))), "sem eventcap: nada atualizado");
        assertEquals(204, status(comToken.patch().uri("/internal/eventcaps/{id}/policy", id)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("capacity", 9))));
        assertEquals(9, eventCapRepository.findByEventId(id).getQuant());
    }

    @Test
    public void eventcapEmLoteDevolveSoOsQueExistem() {
        long a = novoEvento(2);
        long b = novoEvento(7);
        List<EventCap> caps = comToken.post().uri("/internal/eventcaps/batch").contentType(MediaType.APPLICATION_JSON)
                .body(List.of(a, b, novoId())).retrieve().body(new ParameterizedTypeReference<List<EventCap>>() { });
        assertNotNull(caps);
        assertEquals(2, caps.size());
        assertTrue(caps.stream().anyMatch(c -> c.getEventId() == a && c.getCapacity() == 2));
        assertTrue(caps.stream().anyMatch(c -> c.getEventId() == b && c.getCapacity() == 7));
    }

    /** O antigo {@code ON DELETE RESTRICT}: hold em QUALQUER status impede o delete; sem hold (ou sem eventcap) apaga / é no-op. */
    @Test
    public void apagarEventcapRecusaSeHaHoldDeQualquerStatusESenaoRemove() {
        long comHeld = novoEvento(2);
        cliente.hold(new HoldRequest(UUID.randomUUID(), novoId(), comHeld, HoldRequest.CAS));
        assertEquals(409, status(comToken.delete().uri("/internal/eventcaps/{id}", comHeld)));
        assertNotNull(eventCapRepository.findByEventId(comHeld));

        long comLiberado = novoEvento(2);
        UUID h = UUID.randomUUID();
        long usuario = novoId();
        cliente.hold(new HoldRequest(h, usuario, comLiberado, HoldRequest.CAS));
        cliente.release(h, comLiberado, usuario);
        assertEquals(409, status(comToken.delete().uri("/internal/eventcaps/{id}", comLiberado)), "released também conta");

        long semHold = novoEvento(2);
        assertEquals(204, status(comToken.delete().uri("/internal/eventcaps/{id}", semHold)));
        assertNull(eventCapRepository.findByEventId(semHold));
        assertEquals(204, status(comToken.delete().uri("/internal/eventcaps/{id}", semHold)), "sem eventcap: no-op");
    }
}
