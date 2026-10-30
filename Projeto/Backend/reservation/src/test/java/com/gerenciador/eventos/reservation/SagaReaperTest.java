package com.gerenciador.eventos.reservation;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.TestSupport.StockEmbutido;
import com.gerenciador.eventos.TestSupport.StockProxy;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.stockclient.api.HoldRequest;

/**
 * Reaper de sagas órfãs (issue #47, passada b): W2, W4, W7 e W13 da spec, com o stock REAL atrás do {@link StockProxy} e o
 * {@link SagaReaperService} VIVO (agendado a cada {@value #SCAN_MS} ms, órfã depois de {@value #ORPHAN_MS} ms). O reaper desta classe vê o
 * banco inteiro; por isso o contexto é fechado no fim ({@code @DirtiesContext}) e o reaper vem desligado nos demais testes (surefire).
 * Todo teste termina com as invariantes ({@link InvariantesDaSaga}), inclusive "nenhuma saga reserving ou compensating".
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "reservation.saga.reaper.enabled=true",
        "reservation.saga.reaper.scan-ms=" + SagaReaperTest.SCAN_MS,
        "reservation.saga.orphan-ms=" + SagaReaperTest.ORPHAN_MS,
        "reservation.expiry.scan.ms=3600000"})
public class SagaReaperTest extends ReservationTestSupport {

    static final long SCAN_MS = 200;
    static final long ORPHAN_MS = 1000;

    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventWalletRepository eventWalletRepository;
    @Autowired private DatabaseConnection databaseConnection;
    @Autowired private ReservationSaga saga;

    private final StockProxy proxy = StockEmbutido.get().proxy();
    private SagaDb sdb;

    @BeforeEach
    void preparar() throws Exception {
        sdb = new SagaDb(databaseConnection);
        proxy.restaurar();
        proxy.limparChamadas();
        ReflectionTestUtils.setField(saga, "failpoint", "");
        sdb.limparSagasEmCurso();
    }

    @AfterEach
    void restaurar() {
        proxy.restaurar();
        ReflectionTestUtils.setField(saga, "failpoint", "");
    }

    // ===================== W2: stock fora =====================

    @Test
    public void w2_stockForaDa503SagaFicaCompensatingEOReaperCompensaQuandoOStockVolta() throws Exception {
        Evento e = novoEvento(null, "R-W2", 3);
        Usuario u = novoUsuario("u");
        proxy.fora();

        SagaIndisponivelException ex = assertThrows(SagaIndisponivelException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "r-w2"));

        assertEquals("Reserva não concluída, tente novamente", ex.getMessage());
        assertEquals("compensating", sdb.statusDaSaga("r-w2", u.getId()), "o release também falhou");
        assertNull(eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()), "nenhum walletevent");
        // com o stock ainda fora o reaper tenta (a cada tick) e não resolve: a saga segue compensating
        Thread.sleep(4 * SCAN_MS);
        assertEquals("compensating", sdb.statusDaSaga("r-w2", u.getId()));
        InvariantesDaSaga.verificar(databaseConnection, true, e.getEvent_id());

        proxy.dentro();
        sdb.esperarStatus("r-w2", u.getId(), "compensated", 5000);

        assertEquals("reaper", sdb.resolvidaPor("r-w2", u.getId()));
        UUID hold = sdb.holdDaSaga("r-w2", u.getId());
        assertEquals("released", sdb.statusDoHold(hold), "o hold nunca existiu no stock: a lápide o fecha");
        assertEquals(3, estoque.findByEventId(e.getEvent_id()).getQuant(), "quant intacto");
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());

        // a mesma chave reserva depois (falha não consome a chave)
        EventWallet r = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "r-w2");
        assertEquals("pending", r.getStatus());
        assertEquals("reserved", sdb.statusDaSaga("r-w2", u.getId()));
        assertEquals(2, estoque.findByEventId(e.getEvent_id()).getQuant());
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }

    // ===================== W4: processo morto entre o hold e a tx2 =====================

    @Test
    public void w4_failpointAfterHoldDeixaSagaReservingEOReaperResolveNoPrazo() throws Exception {
        Evento e = novoEvento(null, "R-W4", 3);
        Usuario u = novoUsuario("u");
        ReflectionTestUtils.setField(saga, "failpoint", "after-hold");

        long t0 = System.nanoTime();
        assertThrows(IllegalStateException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "r-w4"));

        UUID hold = sdb.holdDaSaga("r-w4", u.getId());
        assertEquals("reserving", sdb.statusDaSaga("r-w4", u.getId()), "o failpoint não compensa");
        assertEquals("held", sdb.statusDoHold(hold));
        assertEquals(2, estoque.findByEventId(e.getEvent_id()).getQuant(), "a vaga está presa pelo hold órfão");
        assertNull(eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()));
        InvariantesDaSaga.verificar(databaseConnection, true, e.getEvent_id());
        Thread.sleep(ORPHAN_MS / 3);
        assertEquals("reserving", sdb.statusDaSaga("r-w4", u.getId()), "o reaper não toca saga mais nova que ORPHAN_MS");

        sdb.esperarStatus("r-w4", u.getId(), "compensated", 10_000);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        long limite = ORPHAN_MS + SCAN_MS + 1000;
        assertTrue(ms <= limite, "órfã resolvida em " + ms + " ms; limite ORPHAN+SCAN+1s = " + limite);
        assertTrue(ms >= ORPHAN_MS - 100, "resolvida cedo demais: " + ms + " ms (ORPHAN " + ORPHAN_MS + ")");
        System.out.println("[W4] orfa resolvida em " + ms + " ms (ORPHAN_MS=" + ORPHAN_MS + ", SCAN_MS=" + SCAN_MS + ", limite " + limite + ")");
        assertEquals("reaper", sdb.resolvidaPor("r-w4", u.getId()));
        assertEquals("released", sdb.statusDoHold(hold));
        assertEquals(3, estoque.findByEventId(e.getEvent_id()).getQuant());
        assertNull(eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()));
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }

    // ===================== W7: dono lento além de ORPHAN_MS =====================

    /** O reaper vence o dono, que responde 503; sem walletevent, quant intacto. Repetido 10 vezes, alternando o atraso DEPOIS e ANTES do hold. */
    @Test
    public void w7_donoLentoAlemDeOrphanMsOReaperVenceEODonoDa503DezVezes() throws Exception {
        Evento e = novoEvento(null, "R-W7", 20);
        for (int i = 0; i < 10; i++) {
            Usuario u = novoUsuario("u");
            String chave = "r-w7-" + i;
            long atraso = ORPHAN_MS + 800;
            if (i % 2 == 0) {
                proxy.atrasarRespostaDoHoldEm(atraso); // o stock já comitou o hold quando o reaper libera
            } else {
                proxy.atrasarHoldEm(atraso); // o reaper grava a lápide antes de o hold chegar ao stock
            }
            final int it = i;

            SagaIndisponivelException ex = assertThrows(SagaIndisponivelException.class,
                    () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), chave), "iteração " + it);

            assertEquals("Reserva não concluída, tente novamente", ex.getMessage(), "iteração " + i);
            proxy.restaurar();
            assertEquals("compensated", sdb.statusDaSaga(chave, u.getId()), "iteração " + i);
            assertEquals("reaper", sdb.resolvidaPor(chave, u.getId()), "o reaper venceu, iteração " + i);
            assertNull(eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()), "nenhum walletevent, iteração " + i);
            assertEquals("released", sdb.statusDoHold(sdb.holdDaSaga(chave, u.getId())), "iteração " + i);
            assertEquals(20, estoque.findByEventId(e.getEvent_id()).getQuant(), "quant intacto, iteração " + i);
            InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
        }
        assertEquals(0, sdb.contar("SELECT count(*) FROM reservation.walletevent WHERE event_id = ?", e.getEvent_id()));
    }

    // ===================== W13: updated_at pelo DEFAULT =====================

    /**
     * A tx1 não grava {@code updated_at}: vale o DEFAULT now() da coluna, e o reaper enxerga a órfã (com NULL, {@code updated_at < ...}
     * nunca seria verdade).
     */
    @Test
    public void w13_updatedAtPeloDefaultDaTx1EOReaperResolveAOrfa() throws Exception {
        assertTrue(String.valueOf(sdb.escalar("SELECT column_default FROM information_schema.columns WHERE table_schema = 'reservation' "
                + "AND table_name = 'idempotency_key' AND column_name = 'updated_at'")).contains("now()"));
        assertEquals("NO", sdb.escalar("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'reservation' "
                + "AND table_name = 'idempotency_key' AND column_name = 'updated_at'"));
        Evento e = novoEvento(null, "R-W13", 3);
        Usuario u = novoUsuario("u");
        ReflectionTestUtils.setField(saga, "failpoint", "after-hold");

        long t0 = System.nanoTime();
        assertThrows(IllegalStateException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "r-w13"));

        assertNotNull(sdb.escalar("SELECT updated_at FROM reservation.idempotency_key WHERE idempotency_key = 'r-w13'"));
        assertEquals(1, sdb.contar("SELECT count(*) FROM reservation.idempotency_key WHERE idempotency_key = 'r-w13' "
                + "AND abs(extract(epoch FROM (updated_at - created_at))) < 1"), "updated_at = o now() da tx1");
        sdb.esperarStatus("r-w13", u.getId(), "compensated", 10_000);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms <= ORPHAN_MS + SCAN_MS + 1000, "resolvida em " + ms + " ms");
        assertEquals(3, estoque.findByEventId(e.getEvent_id()).getQuant());

        // o mesmo com um INSERT cru da tx1 (só as colunas da tx1, sem updated_at) e o hold pedido ao stock de verdade
        Usuario v = novoUsuario("v");
        UUID hold = UUID.randomUUID();
        estoque.hold(new HoldRequest(hold, v.getId(), e.getEvent_id(), HoldRequest.CAS));
        sdb.executar("INSERT INTO reservation.idempotency_key (idempotency_key, user_id, event_id, hold_id, saga_status) "
                + "VALUES ('r-w13-cru', ?, ?, ?, 'reserving')", v.getId(), e.getEvent_id(), hold);
        assertEquals(2, estoque.findByEventId(e.getEvent_id()).getQuant());
        long ms2 = sdb.esperarStatus("r-w13-cru", v.getId(), "compensated", 10_000);
        assertTrue(ms2 <= ORPHAN_MS + SCAN_MS + 1000, "resolvida em " + ms2 + " ms");
        assertEquals(3, estoque.findByEventId(e.getEvent_id()).getQuant());
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }
}
