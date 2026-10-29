package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.reservationclient.ReservationClient;
import com.gerenciador.eventos.stockclient.api.EventCap;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.reservationclient.api.IdempotencyConflictException;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * PONTA A PONTA do salto {@code gateway -> reservation} (issue #33): o {@link ReservationClient} REAL (o mesmo que o monólito
 * usa) fala por HTTP com o serviço REAL, na porta sorteada, contra o Postgres real. É o teste que mantém o contrato da API
 * interna honesto dos dois lados (DTOs, status HTTP, mapeamento de erro, header {@code Idempotency-Key}); o monólito testa o
 * seu lado contra um servidor falso.
 *
 * <p>O stock é o REAL (embutido, atrás do proxy de testes; a capacidade nasce nele). Relay e consumer desligados: a projeção de disponibilidade nunca anda sozinha, que é o "consumer parado" da prova de
 * overselling do braço naive. O caminho com a projeção real é o de {@code EventAvailabilityProjectionTest}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class ReservationApiE2ETest extends ReservationTestSupport {

    @LocalServerPort private int porta;
    @Value("${internal.api.token}") private String tokenInterno;
    @Autowired private DatabaseConnection databaseConnection;
    @Autowired private EventWalletRepository eventWalletRepository;
    @MockitoSpyBean private ReservationController reservationController;
    @MockitoSpyBean private IdempotencyKeyRepository idempotencyKeyRepository;

    private ReservationClient cliente;

    @BeforeAll
    void abrirCliente() {
        cliente = new ReservationClient("http://localhost:" + porta, tokenInterno, 100, Duration.ofSeconds(2),
                Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30));
    }

    @AfterAll
    void fecharCliente() throws Exception {
        cliente.close();
    }

    // ===================== CAS: uma chamada, nunca oversella =====================

    @Test
    public void casSobConcorrenciaNuncaOversellaPeloHttp() throws Exception {
        Evento e = novoEvento(null, "E2E CAS", 5);
        int tentativas = 40;
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger lotado = new AtomicInteger();

        concorrente(tentativas, i -> {
            try {
                cliente.addLink(new EventWallet(novoUsuario("u").getId(), e.getEvent_id()), null, false);
                ok.incrementAndGet();
            } catch (IllegalArgumentException ex) {
                assertTrue(ex.getMessage().toLowerCase().contains("lotado"), ex.getMessage());
                lotado.incrementAndGet();
            }
        });

        assertEquals(5, ok.get(), "exatamente a capacidade foi reservada");
        assertEquals(tentativas - 5, lotado.get());
        assertEquals(5, contarAtivas(e.getEvent_id()), "overselling = 0");
        assertEquals(0, estoque.findByEventId(e.getEvent_id()).getQuant());
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }

    @Test
    public void casNaoLeADisponibilidadeUmaChamadaSo() {
        Evento e = novoEvento(null, "E2E CAS uma chamada", 3);
        clearInvocations(reservationController);

        cliente.addLink(new EventWallet(novoUsuario("u").getId(), e.getEvent_id()), null, false);

        verify(reservationController, never()).disponibilidade(anyLong());
        verify(reservationController, times(1)).reservar(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("cas"));
    }

    // ===================== naive: DUAS chamadas, a janela inclui a rede =====================

    /** O braço naive faz o GET da disponibilidade e DEPOIS o POST da reserva: duas idas e voltas. */
    @Test
    public void naiveFazDuasChamadasHttpGetDaDisponibilidadeEDepoisPostDaReserva() {
        Evento e = novoEvento(null, "E2E naive duas chamadas", 3);
        clearInvocations(reservationController);

        cliente.addLink(new EventWallet(novoUsuario("u").getId(), e.getEvent_id()), null, true);

        InOrder ordem = inOrder(reservationController);
        ordem.verify(reservationController).disponibilidade(e.getEvent_id());
        ordem.verify(reservationController).reservar(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("naive"));
    }

    /**
     * Overselling do braço de controle COM o salto de rede: com o consumer parado a projeção diz 1 vaga para todos, todos
     * leem {@code disponivel > 0} na 1ª chamada e todos passam na 2ª; {@code eventcap} não se mexe.
     */
    @Test
    public void naiveSobConcorrenciaOversellaComConsumerParado() throws Exception {
        Evento e = novoEvento(null, "E2E naive oversell", 1);
        int tentativas = 10;
        AtomicInteger ok = new AtomicInteger();

        concorrente(tentativas, i -> {
            cliente.addLink(new EventWallet(novoUsuario("u").getId(), e.getEvent_id()), null, true);
            ok.incrementAndGet();
        });

        assertEquals(tentativas, ok.get(), "todos passaram a checagem defasada");
        assertEquals(tentativas, contarAtivas(e.getEvent_id()), "overselling: " + tentativas + " reservas para 1 vaga");
        assertEquals(1, estoque.findByEventId(e.getEvent_id()).getQuant(), "o naive nunca toca o CAS");
    }

    /** Disponibilidade 0 na projeção => "Evento lotado" já na 1ª chamada, SEM a 2ª (a rejeição do naive é barata). */
    @Test
    public void naiveComDisponibilidadeZeroRejeitaNaPrimeiraChamada() throws Exception {
        Evento e = novoEvento(null, "E2E naive lotado", 1);
        projetar(e.getEvent_id(), 1);
        clearInvocations(reservationController, idempotencyKeyRepository);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                cliente.addLink(new EventWallet(novoUsuario("u").getId(), e.getEvent_id()), null, true));

        assertTrue(ex.getMessage().toLowerCase().contains("lotado"), ex.getMessage());
        verify(reservationController, times(1)).disponibilidade(e.getEvent_id());
        verify(reservationController, never()).reservar(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), anyString());
        verify(idempotencyKeyRepository, never()).lerReservada(anyString(), anyLong()); // chave gerada: nem consulta
    }

    /** Com chave do CLIENTE a repetição de uma reserva que já vale devolve o resultado salvo, não "lotado". */
    @Test
    public void naiveRepeticaoComChaveDoClienteDevolveOResultadoSalvoMesmoLotado() throws Exception {
        Evento e = novoEvento(null, "E2E naive repeticao", 1);
        Usuario u = novoUsuario("u");

        EventWallet primeira = cliente.addLink(new EventWallet(u.getId(), e.getEvent_id()), "e2e-naive-chave", true);
        projetar(e.getEvent_id(), 1); // o consumer aplicou: disponivel = 0
        clearInvocations(idempotencyKeyRepository);
        EventWallet repetida = cliente.addLink(new EventWallet(u.getId(), e.getEvent_id()), "e2e-naive-chave", true);

        assertEquals("pending", repetida.getStatus());
        assertEquals(primeira.getExpiresAt(), repetida.getExpiresAt());
        assertEquals(1, contarAtivas(e.getEvent_id()));
        verify(idempotencyKeyRepository, times(1)).lerReservada(anyString(), anyLong());

        // outro usuário, mesma chave, lotado: não é repetição de ninguém => lotado
        assertThrows(IllegalArgumentException.class, () ->
                cliente.addLink(new EventWallet(novoUsuario("o").getId(), e.getEvent_id()), "e2e-naive-outra", true));
    }

    @Test
    public void naiveSemEventcapDizCapacidadeNaoConfigurada() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                cliente.addLink(new EventWallet(novoUsuario("u").getId(), 987_654_321L), null, true));
        assertEquals("Capacidade do evento não configurada", ex.getMessage());
        assertNull(cliente.disponibilidade(987_654_321L));
    }

    // ===================== reserva, confirmação, cancelamento =====================

    @Test
    public void cicloReservaConfirmaListaCancelaPeloHttp() throws Exception {
        Evento e = novoEvento(null, "E2E ciclo", 2);
        Usuario u = novoUsuario("u");

        EventWallet reservada = cliente.addLink(new EventWallet(u.getId(), e.getEvent_id()), null, false);
        assertEquals("pending", reservada.getStatus());
        assertNotNull(reservada.getExpiresAt());
        assertNotNull(reservada.getIdempotencyKey(), "a chave gerada volta no header Idempotency-Key");
        assertTrue(cliente.existsActive(u.getId(), e.getEvent_id()));

        assertEquals("confirmed", cliente.confirm(u.getId(), e.getEvent_id()).getStatus());
        List<EventWallet> lista = cliente.listByUser(u.getId());
        assertEquals(1, lista.size());
        assertEquals("confirmed", lista.get(0).getStatus());
        assertEquals(e.getEvent_id(), lista.get(0).getEventId());

        IllegalArgumentException dup = assertThrows(IllegalArgumentException.class, () ->
                cliente.addLink(new EventWallet(u.getId(), e.getEvent_id()), null, false));
        assertTrue(dup.getMessage().toLowerCase().contains("já existente"), dup.getMessage());

        cliente.removeLink(u.getId(), e.getEvent_id());
        assertFalse(cliente.existsActive(u.getId(), e.getEvent_id()));
        assertEquals(1, estoque.findByEventId(e.getEvent_id()).getQuant(), "a vaga volta pelo consumer do stock, não na hora");
        drenarDevolucoes();
        assertEquals(2, estoque.findByEventId(e.getEvent_id()).getQuant(), "cancelar devolve a vaga (pelo consumer do stock)");
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
        cliente.removeLink(u.getId(), e.getEvent_id()); // idempotente

        IllegalArgumentException semPendente = assertThrows(IllegalArgumentException.class,
                () -> cliente.confirm(u.getId(), e.getEvent_id()));
        assertNotNull(semPendente.getMessage());
    }

    @Test
    public void idempotencyKeyPeloHttp_repeticaoMesmoResultadoEOutroEventoDa422() {
        Evento e1 = novoEvento(null, "E2E idem 1", 5);
        Evento e2 = novoEvento(null, "E2E idem 2", 5);
        Usuario u = novoUsuario("u");

        EventWallet primeira = cliente.addLink(new EventWallet(u.getId(), e1.getEvent_id()), "e2e-chave", false);
        assertEquals("e2e-chave", primeira.getIdempotencyKey());
        EventWallet repetida = cliente.addLink(new EventWallet(u.getId(), e1.getEvent_id()), "e2e-chave", false);
        assertEquals(primeira.getExpiresAt(), repetida.getExpiresAt());
        assertEquals(4, estoque.findByEventId(e1.getEvent_id()).getQuant(), "a repetição não debita de novo");

        assertThrows(IdempotencyConflictException.class, () ->
                cliente.addLink(new EventWallet(u.getId(), e2.getEvent_id()), "e2e-chave", false));
        assertEquals(5, estoque.findByEventId(e2.getEvent_id()).getQuant());

        // chave longa demais: regra de negócio (400)
        IllegalArgumentException longa = assertThrows(IllegalArgumentException.class, () ->
                cliente.addLink(new EventWallet(u.getId(), e2.getEvent_id()), "x".repeat(256), false));
        assertTrue(longa.getMessage().contains("255"), longa.getMessage());
    }

    @Test
    public void userIdEhParametroExplicitoCadaUsuarioTemASuaReserva() {
        Evento e = novoEvento(null, "E2E dois usuarios", 3);
        Usuario a = novoUsuario("a");
        Usuario b = novoUsuario("b");
        cliente.addLink(new EventWallet(a.getId(), e.getEvent_id()), null, false);

        assertTrue(cliente.existsActive(a.getId(), e.getEvent_id()));
        assertFalse(cliente.existsActive(b.getId(), e.getEvent_id()));
        assertEquals(1, cliente.listByUser(a.getId()).size());
        assertTrue(cliente.listByUser(b.getId()).isEmpty());
    }

    // ===================== token de serviço (autenticação das chamadas internas) =====================

    /** Sem o header, ou com o valor errado, todo {@code /internal/**} responde 401 e nada chega ao controller. */
    @Test
    public void semTokenOuComTokenErradoTodaRotaInternaDa401() {
        RestClient cru = RestClient.builder().baseUrl("http://localhost:" + porta).build();
        String[] rotas = {"/internal/reservations/active?userId=1&eventId=1", "/internal/events/1/availability",
                "/internal/reservations/user/1"};
        for (String rota : rotas) {
            int semToken = cru.get().uri(rota).exchange((req, resp) -> resp.getStatusCode().value());
            int errado = cru.get().uri(rota).header(ReservationClient.HEADER_TOKEN_INTERNO, tokenInterno + "x")
                    .exchange((req, resp) -> resp.getStatusCode().value());
            int vazio = cru.get().uri(rota).header(ReservationClient.HEADER_TOKEN_INTERNO, "")
                    .exchange((req, resp) -> resp.getStatusCode().value());
            assertEquals(401, semToken, rota);
            assertEquals(401, errado, rota);
            assertEquals(401, vazio, rota);
        }
        // escrita sem token: 401 e NADA gravado
        int post = cru.post().uri("/internal/reservations/confirm").contentType(MediaType.APPLICATION_JSON)
                .body("{\"userId\":1,\"eventId\":1}").exchange((req, resp) -> resp.getStatusCode().value());
        assertEquals(401, post);
    }

    /**
     * O filtro não pode decidir por prefixo do URI cru: {@code /internal;x=1/...} (matrix param) e {@code /%69nternal/...}
     * (percent-encoding) são normalizados pelo Spring MVC para a rota interna e passariam sem token. Toda requisição sem token é 401,
     * inclusive caminho fora de {@code /internal}.
     */
    @Test
    public void caminhosDisfarcadosEForaDeInternalTambemDao401SemToken() {
        RestClient cru = RestClient.builder().baseUrl("http://localhost:" + porta).build();
        java.net.URI base = java.net.URI.create("http://localhost:" + porta);
        int get = cru.get().uri(base.resolve("/internal;x=1/reservations/user/1")).exchange((req, resp) -> resp.getStatusCode().value());
        int post = cru.post().uri(base.resolve("/%69nternal/reservations?path=cas")).contentType(MediaType.APPLICATION_JSON)
                .body("{\"userId\":1,\"eventId\":1}").exchange((req, resp) -> resp.getStatusCode().value());
        int delete = cru.delete().uri(base.resolve("/internal;a/reservations?userId=1&eventId=1"))
                .exchange((req, resp) -> resp.getStatusCode().value());
        int fora = cru.get().uri(base.resolve("/qualquer/coisa")).exchange((req, resp) -> resp.getStatusCode().value());
        int raiz = cru.get().uri(base.resolve("/")).exchange((req, resp) -> resp.getStatusCode().value());
        assertEquals(401, get);
        assertEquals(401, post);
        assertEquals(401, delete);
        assertEquals(401, fora);
        assertEquals(401, raiz);
    }

    /** Com o token certo o fluxo é o normal (é o que todos os outros testes deste arquivo fazem pelo cliente real). */
    @Test
    public void comTokenCertoOFluxoEhNormal() {
        RestClient cru = RestClient.builder().baseUrl("http://localhost:" + porta).build();
        int status = cru.get().uri("/internal/reservations/active?userId=1&eventId=1")
                .header(ReservationClient.HEADER_TOKEN_INTERNO, tokenInterno).exchange((req, resp) -> resp.getStatusCode().value());
        assertEquals(200, status);
        // o 401 vira RuntimeException no cliente (token errado no gateway = falha alta, nunca "vazio")
        ReservationClient tokenErrado = new ReservationClient("http://localhost:" + porta, "outro-token", 2, Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(20));
        try {
            RuntimeException ex = assertThrows(RuntimeException.class, () -> tokenErrado.existsActive(1L, 1L));
            assertTrue(ex.getMessage().contains("401"), ex.getMessage());
        } finally {
            try {
                tokenErrado.close();
            } catch (java.io.IOException ignorada) {
                // nada a fechar além do pool
            }
        }
    }

    /** Sem a variável o serviço (e o cliente) FALHAM NO BOOT com mensagem clara: sem default, sem subir aberto. */
    @Test
    public void semTokenConfiguradoServicoEClienteNaoSobem() {
        IllegalStateException servico = assertThrows(IllegalStateException.class, () -> new InternalTokenFilter(""));
        assertTrue(servico.getMessage().contains("INTERNAL_API_TOKEN"), servico.getMessage());
        assertThrows(IllegalStateException.class, () -> new InternalTokenFilter("   "));
        IllegalStateException clienteSemToken = assertThrows(IllegalStateException.class, () -> new ReservationClient(
                "http://localhost:1", "", 2, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertTrue(clienteSemToken.getMessage().contains("INTERNAL_API_TOKEN"), clienteSemToken.getMessage());
    }

    /** Pedido malformado é erro do chamador (400), não falha do serviço (500). */
    @Test
    public void corpoMalformadoEParametroAusenteDao400() {
        RestClient cru = RestClient.builder().baseUrl("http://localhost:" + porta)
                .defaultHeader(ReservationClient.HEADER_TOKEN_INTERNO, tokenInterno).build();
        int jsonRuim = cru.post().uri("/internal/reservations").contentType(MediaType.APPLICATION_JSON).body("{nao e json")
                .exchange((req, resp) -> resp.getStatusCode().value());
        int semParametro = cru.get().uri("/internal/reservations/active")
                .exchange((req, resp) -> resp.getStatusCode().value());
        int tipoErrado = cru.get().uri("/internal/reservations/user/abc")
                .exchange((req, resp) -> resp.getStatusCode().value());
        assertEquals(400, jsonRuim);
        assertEquals(400, semParametro);
        assertEquals(400, tipoErrado);
    }

    // ===================== helpers =====================

    private interface Tarefa {
        void rodar(int i) throws Exception;
    }

    /** Roda {@code n} tarefas largando juntas (barreira) e propaga a primeira exceção inesperada. */
    private static void concorrente(int n, Tarefa tarefa) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier largada = new CyclicBarrier(n);
        List<Future<?>> futuros = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int idx = i;
            futuros.add(pool.submit(() -> {
                largada.await();
                tarefa.rodar(idx);
                return null;
            }));
        }
        try {
            for (Future<?> f : futuros) {
                f.get(60, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Reservas ativas ({@code pending}/{@code confirmed}) do evento no banco. */
    private int contarAtivas(Long eventId) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM reservation.walletevent "
                     + "WHERE event_id = ? AND status IN ('pending', 'confirmed')")) {
            ps.setLong(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void projetar(Long eventId, int ativos) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO reservation.event_availability (event_id, ativos, last_occurred_at) VALUES (?, ?, now()) "
                     + "ON CONFLICT (event_id) DO UPDATE SET ativos = EXCLUDED.ativos")) {
            ps.setLong(1, eventId);
            ps.setInt(2, ativos);
            ps.executeUpdate();
        }
    }
}
