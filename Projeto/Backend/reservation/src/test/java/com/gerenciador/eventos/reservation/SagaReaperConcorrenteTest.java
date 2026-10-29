package com.gerenciador.eventos.reservation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/**
 * W9 (issue #47): 4 reapers simultâneos ({@code CyclicBarrier}) sobre 30 órfãs. O {@link SagaReaperService} existe, mas o timer dele fica
 * a 1 h (os ticks são chamados à mão), então só as quatro chamadas concorrentes mexem nas sagas. O {@code SKIP LOCKED} reparte as linhas
 * entre as transações de seleção; a transição para {@code compensated} é um CAS, então cada saga é fechada por UM reaper só (a soma do que
 * os quatro relatam é exatamente 30) e cada vaga volta uma vez.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "reservation.saga.reaper.enabled=true",
        "reservation.saga.reaper.scan-ms=3600000",
        "reservation.saga.orphan-ms=500",
        "reservation.expiry.scan.ms=3600000"})
public class SagaReaperConcorrenteTest extends ReservationTestSupport {

    private static final int ORFAS = 30;

    @Autowired private EventWalletService eventWalletService;
    @Autowired private DatabaseConnection databaseConnection;
    @Autowired private ReservationSaga saga;
    @Autowired private SagaReaperService reaper;

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

    @Test
    public void w9_quatroReapersSimultaneosSobreTrintaOrfasCadaUmaLiberadaUmaVez() throws Exception {
        Evento e = novoEvento(null, "R-W9", 40);
        // uma reserva viva fica no evento: o quant final (39) fica ABAIXO da capacidade, então uma devolução a mais seria vista
        // (sem ela, o LEAST(quant + 1, capacity) cortaria a devolução dupla e a asserção de "nem a mais" nunca falharia)
        Usuario viva = novoUsuario("viva");
        assertEquals("pending", eventWalletService.addLink(new EventWallet(viva.getId(), e.getEvent_id()), "r-w9-viva").getStatus());
        ReflectionTestUtils.setField(saga, "failpoint", "after-hold");
        for (int i = 0; i < ORFAS; i++) {
            Usuario u = novoUsuario("u");
            assertThrows(IllegalStateException.class,
                    () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "r-w9-" + u.getId()));
        }
        ReflectionTestUtils.setField(saga, "failpoint", "");
        assertEquals(ORFAS, sdb.contar("SELECT count(*) FROM reservation.idempotency_key WHERE event_id = ? AND saga_status = 'reserving'",
                e.getEvent_id()));
        assertEquals(40 - 1 - ORFAS, estoque.findByEventId(e.getEvent_id()).getQuant(), "a reserva viva e as 30 órfãs seguram vagas");
        Thread.sleep(700); // todas passam de ORPHAN_MS
        ReflectionTestUtils.setField(reaper, "lote", 7); // vários lotes por reaper: as seleções se intercalam
        proxy.limparChamadas();

        List<Integer> fechadas = Concorrencia.emParalelo(4, () -> reaper.executar());

        int soma = fechadas.stream().mapToInt(Integer::intValue).sum();
        assertEquals(ORFAS, soma, "cada saga é fechada por um reaper só: " + fechadas);
        assertEquals(ORFAS, sdb.contar("SELECT count(*) FROM reservation.idempotency_key WHERE event_id = ? AND saga_status = 'compensated' "
                + "AND resolved_by = 'reaper'", e.getEvent_id()));
        assertEquals(ORFAS, sdb.contar("SELECT count(*) FROM stock.hold WHERE event_id = ? AND status = 'released'", e.getEvent_id()));
        assertEquals(1, sdb.contar("SELECT count(*) FROM stock.hold WHERE event_id = ? AND status = 'held'", e.getEvent_id()), "só o hold da reserva viva");
        assertEquals(40 - 1, estoque.findByEventId(e.getEvent_id()).getQuant(), "cada vaga voltou uma vez (nem a mais): só a reserva viva segue debitada");
        assertTrue(proxy.releasesRecebidos() >= ORFAS, "pelo menos um release por saga: " + proxy.releasesRecebidos());
        assertEquals(1, sdb.contar("SELECT count(*) FROM reservation.walletevent WHERE event_id = ?", e.getEvent_id()), "só a reserva viva");
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }
}
