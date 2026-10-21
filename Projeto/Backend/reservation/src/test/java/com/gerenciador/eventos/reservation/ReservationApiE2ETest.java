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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.reservationclient.ReservationClient;
import com.gerenciador.eventos.reservationclient.api.EventCap;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.reservationclient.api.IdempotencyConflictException;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * PONTA A PONTA do salto {@code gateway -> reservation} (issue #33): o {@link ReservationClient} REAL (o mesmo que o monólito
 * usa) fala por HTTP com o serviço REAL, na porta sorteada, contra o Postgres real. É o teste que mantém o contrato da API
 * interna honesto dos dois lados (DTOs, status HTTP, mapeamento de erro, header {@code Idempotency-Key}); o monólito testa o
 * seu lado contra um servidor falso.
 *
 * <p>Relay e consumer desligados: a projeção de disponibilidade nunca anda sozinha, que é o "consumer parado" da prova de
 * overselling do braço naive. O caminho com a projeção real é o de {@code EventAvailabilityProjectionTest}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class ReservationApiE2ETest extends ReservationTestSupport {

    @LocalServerPort private int porta;
    @Autowired private DatabaseConnection databaseConnection;
    @Autowired private EventWalletRepository eventWalletRepository;
    @MockitoSpyBean private ReservationController reservationController;
    @MockitoSpyBean private IdempotencyKeyRepository idempotencyKeyRepository;

    private ReservationClient cliente;

    @BeforeAll
    void abrirCliente() {
        cliente = new ReservationClient("http://localhost:" + porta, 100, Duration.ofSeconds(2),
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
        assertEquals(0, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());
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
        assertEquals(1, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(), "o naive nunca toca o CAS");
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
        verify(idempotencyKeyRepository, never()).find(anyString(), anyLong()); // chave gerada: nem consulta
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
        verify(idempotencyKeyRepository, times(1)).find(anyString(), anyLong());

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
    public void cicloReservaConfirmaListaCancelaPeloHttp() {
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
        assertEquals(2, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(), "cancelar devolve a vaga");
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
        assertEquals(4, eventCapRepository.findByEventId(e1.getEvent_id()).getQuant(), "a repetição não debita de novo");

        assertThrows(IdempotencyConflictException.class, () ->
                cliente.addLink(new EventWallet(u.getId(), e2.getEvent_id()), "e2e-chave", false));
        assertEquals(5, eventCapRepository.findByEventId(e2.getEvent_id()).getQuant());

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

    // ===================== API de capacidade (eventcap) =====================

    @Test
    public void criacaoDoEventcapEhIdempotentePorEventId() {
        long id = novoEvento(null, "E2E cap idem", 3).getEvent_id();
        // já existe (novoEvento criou com 3): criar de novo com outra capacidade NÃO altera nada
        cliente.create(new EventCap(id, 99, 99, null));
        assertEquals(3, cliente.findByEventId(id).getCapacity());

        long novo = 2_000_000_000L + id;
        EventCap criada = new EventCap(novo, 7, 7, LocalDateTime.now().plusDays(3).withNano(0));
        cliente.create(criada);
        cliente.create(new EventCap(novo, 1, 1, null)); // repetição
        EventCap lida = cliente.findByEventId(novo);
        assertEquals(7, lida.getCapacity());
        assertEquals(7, lida.getQuant());
        assertEquals(criada.getBuyTimeLimit(), lida.getBuyTimeLimit());
        assertEquals(120, lida.getMaxTimeInLine());
        assertFalse(lida.isUnlimited());

        // capacidade inválida: 400
        assertThrows(IllegalArgumentException.class, () -> cliente.create(new EventCap(novo + 1, 0, 0, null)));
    }

    @Test
    public void busca_unitaria_em_lote_e_inexistente() {
        long a = novoEvento(null, "E2E busca a", 4).getEvent_id();
        long b = novoEvento(null, "E2E busca b", -1).getEvent_id();

        Map<Long, EventCap> mapa = cliente.findByEventIds(List.of(a, b, 777_777_777L));
        assertEquals(2, mapa.size(), "evento sem eventcap fica fora");
        assertEquals(4, mapa.get(a).getCapacity());
        assertTrue(mapa.get(b).isUnlimited());
        assertTrue(cliente.findByEventIds(List.of()).isEmpty());
        assertNull(cliente.findByEventId(777_777_777L));
    }

    /**
     * A busca de eventos hidrata a LISTA inteira. Com os ids na query string (GET) 5 mil ids (~45 KB) estourariam o limite de
     * header do Tomcat (8 KB) e a busca do /bff falharia; no corpo do POST passam, e o serviço devolve só quem tem eventcap.
     */
    @Test
    public void buscaEmLoteComCincoMilIdsNaoEstouraOLimiteDeHeader() {
        long base = 1_500_000_000L;
        List<Long> ids = new ArrayList<>();
        for (long i = 0; i < 5000; i++) {
            ids.add(base + i);
        }
        cliente.create(new EventCap(ids.get(0), 4, 4, null));
        cliente.create(new EventCap(ids.get(4999), -1, -1, null));

        Map<Long, EventCap> mapa = cliente.findByEventIds(ids);

        assertEquals(2, mapa.size());
        assertEquals(4, mapa.get(ids.get(0)).getCapacity());
        assertTrue(mapa.get(ids.get(4999)).isUnlimited());
    }

    /** Evento sem eventcap: o PATCH não atualiza nada e DIZ (404 => false), em vez de calar e deixar a reconciliação recriar o antigo. */
    @Test
    public void atualizarPoliticaDeEventoSemEventcapDevolveFalse() {
        assertFalse(cliente.updatePolicy(987_000_001L, 5, null));
        assertNull(cliente.findByEventId(987_000_001L), "nada foi criado");
    }

    /** Pedido malformado é erro do chamador (400), não falha do serviço (500). */
    @Test
    public void corpoMalformadoEParametroAusenteDao400() {
        RestClient cru = RestClient.builder().baseUrl("http://localhost:" + porta).build();
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

    @Test
    public void atualizarPoliticaAcompanhaAsVagasERecusaAbaixoDoReservado() {
        Evento e = novoEvento(null, "E2E politica", 3);
        cliente.addLink(new EventWallet(novoUsuario("u1").getId(), e.getEvent_id()), null, false);
        cliente.addLink(new EventWallet(novoUsuario("u2").getId(), e.getEvent_id()), null, false);

        cliente.updatePolicy(e.getEvent_id(), 5, null);
        EventCap cap = cliente.findByEventId(e.getEvent_id());
        assertEquals(5, cap.getCapacity());
        assertEquals(3, cap.getQuant(), "5 de capacidade - 2 reservas");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> cliente.updatePolicy(e.getEvent_id(), 1, null));
        assertTrue(ex.getMessage().contains("menor"), ex.getMessage());
        assertEquals(5, cliente.findByEventId(e.getEvent_id()).getCapacity(), "recusado: nada mudou");
    }

    /** O antigo {@code ON DELETE RESTRICT}: com reserva (em qualquer status) o evento não perde o {@code eventcap}. */
    @Test
    public void apagarEventcapRecusaSeHaReservaESenaoRemove() {
        Evento comReserva = novoEvento(null, "E2E apagar com reserva", 2);
        Usuario u = novoUsuario("u");
        cliente.addLink(new EventWallet(u.getId(), comReserva.getEvent_id()), null, false);
        cliente.removeLink(u.getId(), comReserva.getEvent_id()); // cancelled: a linha continua existindo

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> cliente.delete(comReserva.getEvent_id()));
        assertTrue(ex.getMessage().contains("reservas"), ex.getMessage());
        assertNotNull(cliente.findByEventId(comReserva.getEvent_id()));

        Evento semReserva = novoEvento(null, "E2E apagar sem reserva", 2);
        cliente.delete(semReserva.getEvent_id());
        assertNull(cliente.findByEventId(semReserva.getEvent_id()));
        cliente.delete(semReserva.getEvent_id()); // sem eventcap: no-op
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
