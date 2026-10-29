package com.gerenciador.eventos.stock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.stockclient.api.EventCap;
import com.gerenciador.eventos.stockclient.StockClient;
import com.gerenciador.eventos.stockclient.api.HoldLiberadoException;
import com.gerenciador.eventos.stockclient.api.HoldRequest;
import com.gerenciador.eventos.stockclient.api.HoldResponse;

/**
 * O ledger de holds do stock (issue #47), PONTA A PONTA: o {@link StockClient} REAL fala por HTTP com o serviço REAL, contra o
 * Postgres real (S1 a S4, S7 e S8 da spec). É também o teste de contrato do cliente.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class StockHoldTest extends StockTestSupport {

    @LocalServerPort private int porta;
    @Value("${internal.api.token}") private String tokenInterno;

    private StockClient cliente;
    private final ExecutorService pool = Executors.newFixedThreadPool(64);

    @BeforeAll
    void abrirCliente() {
        cliente = new StockClient("http://localhost:" + porta, tokenInterno, 100, Duration.ofSeconds(2),
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(30));
    }

    @AfterAll
    void fecharCliente() throws Exception {
        pool.shutdownNow();
        cliente.close();
    }

    private static HoldRequest cas(UUID hold, long usuario, long evento) {
        return new HoldRequest(hold, usuario, evento, HoldRequest.CAS);
    }

    // ===================== S1: reservar é idempotente por holdId =====================

    @Test
    public void reservarRepetidoDevolveOMesmoHoldSemNovoDebito() throws Exception {
        long ev = novoEvento(3);
        UUID h = UUID.randomUUID();

        HoldResponse primeira = cliente.hold(cas(h, novoId(), ev));
        HoldResponse repeticao = cliente.hold(cas(h, novoId(), ev));

        assertEquals(h, primeira.holdId());
        assertEquals(120, primeira.maxTimeInLine());
        assertEquals(primeira, repeticao);
        assertEquals(2, quant(ev), "um débito só");
        assertEquals(1, contarHolds(ev, "held", true));
        assertInvariante(ev);
    }

    @Test
    public void cinquentaChamadasParalelasComOMesmoHoldIdDebitamUmaVez() throws Exception {
        long ev = novoEvento(5);
        UUID h = UUID.randomUUID();
        long usuario = novoId();

        List<HoldResponse> respostas = emParalelo(50, i -> cliente.hold(cas(h, usuario, ev)));

        assertEquals(50, respostas.size());
        assertTrue(respostas.stream().allMatch(r -> r.holdId().equals(h)));
        assertEquals(4, quant(ev), "um débito só, apesar das 50 chamadas");
        assertEquals(1, contarHolds(ev, "held", true));
        assertInvariante(ev);
    }

    // ===================== S2: release é idempotente =====================

    @Test
    public void releaseDuasVezesEEmParaleloDevolveUmaVagaSo() throws Exception {
        long ev = novoEvento(3);
        long usuario = novoId();
        UUID h1 = UUID.randomUUID();
        UUID h2 = UUID.randomUUID();
        UUID vivo = UUID.randomUUID();
        cliente.hold(cas(h1, usuario, ev));
        cliente.hold(cas(h2, novoId(), ev));
        cliente.hold(cas(vivo, novoId(), ev));
        assertEquals(0, quant(ev));

        cliente.release(h1, ev, usuario);
        cliente.release(h1, ev, usuario);
        assertEquals(1, quant(ev), "duas vezes sequenciais: uma devolução");

        emParalelo(20, i -> {
            cliente.release(h2, ev, usuario);
            return null;
        });
        assertEquals(2, quant(ev), "20 em paralelo: UMA devolução (o hold vivo impede o teto da capacidade de esconder uma devolução em dobro)");
        assertEquals("released", statusDoHold(h1));
        assertEquals("released", statusDoHold(h2));
        assertEquals("held", statusDoHold(vivo));
        assertInvariante(ev);
    }

    // ===================== S3 e S8: lápide =====================

    @Test
    public void releaseAntesDoReservarGravaLapideEOReservarAtrasadoDa409() throws Exception {
        long ev = novoEvento(2);
        long usuario = novoId();
        UUID h = UUID.randomUUID();

        cliente.release(h, ev, usuario);

        assertEquals("released", statusDoHold(h));
        assertEquals(Boolean.FALSE, debitado(h));
        assertThrows(HoldLiberadoException.class, () -> cliente.hold(cas(h, usuario, ev)));
        assertEquals(2, quant(ev), "o reservar atrasado não debita");
        assertEquals("released", statusDoHold(h), "a lápide não volta a held");
    }

    @Test
    public void releaseDeHoldInexistenteDa200EGravaALapideComEventoEUsuarioDoCorpo() throws Exception {
        long ev = novoId(); // sem eventcap: a lápide não depende dele
        long usuario = novoId();
        UUID h = UUID.randomUUID();

        cliente.release(h, ev, usuario);

        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT event_id, user_id, decremented, status FROM stock.hold WHERE hold_id = ?")) {
            ps.setObject(1, h);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "lápide gravada");
                assertEquals(ev, rs.getLong(1));
                assertEquals(usuario, rs.getLong(2));
                assertFalse(rs.getBoolean(3));
                assertEquals("released", rs.getString(4));
            }
        }
    }

    // ===================== S4: overselling 0 =====================

    @Test
    public void nThreadsComHoldsDistintosReservamExatamenteACapacidade() throws Exception {
        int capacidade = 5;
        long ev = novoEvento(capacidade);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger lotado = new AtomicInteger();

        emParalelo(40, i -> {
            try {
                cliente.hold(cas(UUID.randomUUID(), novoId(), ev));
                ok.incrementAndGet();
            } catch (IllegalArgumentException ex) {
                assertEquals("Evento lotado", ex.getMessage());
                lotado.incrementAndGet();
            }
            return null;
        });

        assertEquals(capacidade, ok.get());
        assertEquals(40 - capacidade, lotado.get());
        assertEquals(capacidade, contarHolds(ev, "held", true), "exatamente C holds held, e os rejeitados não deixaram hold");
        assertEquals(0, contarHolds(ev, "released", null));
        assertEquals(0, quant(ev));
        assertInvariante(ev);
    }

    @Test
    public void overSellingZeroComReleasesConcorrentesMisturados() throws Exception {
        int capacidade = 5;
        long ev = novoEvento(capacidade);
        long usuario = novoId();
        List<UUID> iniciais = new ArrayList<>();
        for (int i = 0; i < capacidade; i++) {
            UUID h = UUID.randomUUID();
            cliente.hold(cas(h, usuario, ev));
            iniciais.add(h);
        }
        assertEquals(0, quant(ev));
        AtomicInteger indice = new AtomicInteger();

        emParalelo(40, i -> {
            int n = indice.getAndIncrement();
            if (n % 2 == 0) {
                cliente.release(iniciais.get((n / 2) % capacidade), ev, usuario);
            } else {
                try {
                    cliente.hold(cas(UUID.randomUUID(), novoId(), ev));
                } catch (IllegalArgumentException lotado) {
                    assertEquals("Evento lotado", lotado.getMessage());
                }
            }
            return null;
        });

        assertTrue(quant(ev) >= 0 && quant(ev) <= capacidade);
        assertTrue(contarHolds(ev, "held", true) <= capacidade, "nunca mais holds debitados que a capacidade");
        assertInvariante(ev);
    }

    // ===================== S7: release x reservar em voo =====================

    /**
     * O reservar H está parado no CAS da linha quente (uma 3ª conexão a travou) com o INSERT do hold ainda sem commit, e o release(H)
     * chega: o upsert tem de ESPERAR esse INSERT. Se o release fosse {@code UPDATE ... WHERE status = 'held'} seguido de uma lápide
     * {@code ON CONFLICT DO NOTHING}, o UPDATE não veria o INSERT sem commit (0 linhas), a lápide esperaria e nada faria, e H comitaria
     * {@code held}, com a vaga presa para sempre. Sincronizado pelo {@code pg_stat_activity}, sem sleeps: o release só parte quando o
     * reservar já espera, e a trava só cai quando os DOIS esperam.
     */
    @Test
    public void releaseComReservarEmVooEsperaEDevolveAVagaDoHold() throws Exception {
        long ev = novoEvento(1);
        long usuario = novoId();
        UUID h = UUID.randomUUID();

        try (Connection trava = db.getConnection()) {
            trava.setAutoCommit(false);
            try (Statement st = trava.createStatement()) {
                st.execute("SELECT 1 FROM stock.eventcap WHERE event_id = " + ev + " FOR UPDATE");
            }
            Future<HoldResponse> reservar = pool.submit(() -> cliente.hold(cas(h, usuario, ev)));
            aguardarEsperandoLock(1);
            Future<?> liberar = pool.submit(() -> {
                cliente.release(h, ev, usuario);
                return null;
            });
            aguardarEsperandoLock(2);
            trava.commit();
            assertNotNull(reservar.get(30, TimeUnit.SECONDS));
            liberar.get(30, TimeUnit.SECONDS);
        }

        assertEquals("released", statusDoHold(h), "o release esperou o reservar e liberou o hold (não ficou held)");
        assertEquals(Boolean.TRUE, debitado(h), "o hold chegou a debitar...");
        assertEquals(1, quant(ev), "...e a vaga voltou: quant intacto");
        assertInvariante(ev);
    }

    /** O mesmo, mas o reservar termina em "lotado" (rollback do INSERT): o release que esperou grava a lápide. */
    @Test
    public void releaseComReservarEmVooQueTerminaLotadoGravaALapide() throws Exception {
        long ev = novoEvento(1);
        long usuario = novoId();
        UUID h = UUID.randomUUID();

        try (Connection trava = db.getConnection()) {
            trava.setAutoCommit(false);
            // quant visível 1: o CAS entra na fila da linha (com quant já 0 ele falharia na hora, sem esperar)
            try (Statement st = trava.createStatement()) {
                st.execute("UPDATE stock.eventcap SET quant = 0 WHERE event_id = " + ev);
            }
            Future<HoldResponse> reservar = pool.submit(() -> cliente.hold(cas(h, usuario, ev)));
            aguardarEsperandoLock(1);
            Future<?> liberar = pool.submit(() -> {
                cliente.release(h, ev, usuario);
                return null;
            });
            aguardarEsperandoLock(2);
            trava.commit();
            Exception lotado = assertThrows(java.util.concurrent.ExecutionException.class, () -> reservar.get(30, TimeUnit.SECONDS));
            assertEquals("Evento lotado", lotado.getCause().getMessage());
            liberar.get(30, TimeUnit.SECONDS);
        }

        assertEquals("released", statusDoHold(h), "lápide gravada depois do rollback do reservar");
        assertEquals(Boolean.FALSE, debitado(h));
        assertEquals(0, quant(ev), "o 0 é o da 3ª conexão: nada foi devolvido nem debitado");
        assertThrows(HoldLiberadoException.class, () -> cliente.hold(cas(h, usuario, ev)));
    }

    // ===================== modos, erros e regras do reservar =====================

    @Test
    public void modoNaiveEEventoIlimitadoCriamHoldSemDebito() throws Exception {
        long limitado = novoEvento(3);
        long ilimitado = novoEvento(EventCap.UNLIMITED);
        UUID naive = UUID.randomUUID();
        UUID semLimite = UUID.randomUUID();
        long usuario = novoId();

        cliente.hold(new HoldRequest(naive, usuario, limitado, HoldRequest.NAIVE));
        cliente.hold(cas(semLimite, usuario, ilimitado));

        assertEquals(3, quant(limitado), "o naive nunca toca o eventcap");
        assertEquals(EventCap.UNLIMITED, quant(ilimitado));
        assertEquals(Boolean.FALSE, debitado(naive));
        assertEquals(Boolean.FALSE, debitado(semLimite));
        assertEquals("held", statusDoHold(naive));
        assertEquals("held", statusDoHold(semLimite));

        cliente.release(naive, limitado, usuario);
        cliente.release(semLimite, ilimitado, usuario);
        assertEquals(3, quant(limitado), "sem débito, sem devolução");
        assertEquals(EventCap.UNLIMITED, quant(ilimitado));
    }

    @Test
    public void errosDeNegocioSaoOs400DaSpecNaOrdemCapacidadeJanelaLotado() throws Exception {
        long semCap = novoId();
        IllegalArgumentException a = assertThrows(IllegalArgumentException.class,
                () -> cliente.hold(cas(UUID.randomUUID(), novoId(), semCap)));
        assertEquals("Capacidade do evento não configurada", a.getMessage());

        long fechado = novoId();
        eventCapRepository.create(new EventCap(fechado, 3, 3, LocalDateTime.now().minusHours(1)));
        IllegalArgumentException b = assertThrows(IllegalArgumentException.class,
                () -> cliente.hold(cas(UUID.randomUUID(), novoId(), fechado)));
        assertEquals("Janela de compra encerrada para este evento", b.getMessage());
        assertEquals(0, contarHolds(fechado, "held", null), "erro de negócio não deixa hold");

        long cheio = novoEvento(1);
        cliente.hold(cas(UUID.randomUUID(), novoId(), cheio));
        UUID rejeitado = UUID.randomUUID();
        IllegalArgumentException c = assertThrows(IllegalArgumentException.class, () -> cliente.hold(cas(rejeitado, novoId(), cheio)));
        assertEquals("Evento lotado", c.getMessage());
        assertEquals(null, statusDoHold(rejeitado), "o rollback do lotado leva o hold junto");
    }

    @Autowired private HoldService holdService;

    /**
     * A repetição de um hold que já debitou volta 200 mesmo depois de a janela de compra fechar (a resposta do 1o reservar pode ter
     * se perdido): se desse 400, o wallet trataria como "não houve débito" e a vaga ficaria presa. Hold NOVO no mesmo evento: 400.
     */
    @Test
    public void repeticaoDeHoldDebitadoDa200MesmoComAJanelaFechadaEHoldNovoDa400() throws Exception {
        long ev = novoId();
        eventCapRepository.create(new EventCap(ev, 3, 3, LocalDateTime.now().plusHours(1)));
        UUID h = UUID.randomUUID();
        long usuario = novoId();
        HoldService.Reservado primeiro = holdService.reservar(cas(h, usuario, ev));
        assertTrue(primeiro.criado());
        assertEquals(2, quant(ev));

        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE stock.eventcap SET buy_time_limit = now() - interval '1 hour' WHERE event_id = " + ev);
        }

        HoldService.Reservado repeticao = holdService.reservar(cas(h, usuario, ev));
        assertFalse(repeticao.criado(), "200, não 201");
        assertEquals(primeiro.resposta(), repeticao.resposta());
        assertEquals(2, quant(ev), "quant igual: a repetição não debita nem devolve");
        assertEquals("held", statusDoHold(h));

        UUID novo = UUID.randomUUID();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> cliente.hold(cas(novo, novoId(), ev)));
        assertEquals("Janela de compra encerrada para este evento", ex.getMessage());
        assertEquals(null, statusDoHold(novo), "o rollback da janela leva o INSERT do hold junto");
        assertEquals(2, quant(ev));
        assertInvariante(ev);
    }

    @Test
    public void liberarComIdsInvalidosDa400ENaoGravaLapide() throws Exception {
        UUID h = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> holdService.liberar(h, 0L, 5L));
        assertThrows(IllegalArgumentException.class, () -> holdService.liberar(h, 5L, -1L));
        assertThrows(IllegalArgumentException.class, () -> holdService.liberar(h, null, 5L));
        assertEquals(null, statusDoHold(h), "id inválido não grava lápide");
    }

    // ===================== helpers =====================

    private interface Tarefa<T> {
        T rodar(int i) throws Exception;
    }

    /** Roda {@code n} tarefas largando juntas e devolve os resultados; propaga a primeira exceção inesperada. */
    private <T> List<T> emParalelo(int n, Tarefa<T> tarefa) throws Exception {
        AtomicInteger indice = new AtomicInteger();
        List<Callable<T>> tarefas = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tarefas.add(() -> tarefa.rodar(indice.getAndIncrement()));
        }
        return Concorrencia.emParalelo(tarefas);
    }
}
