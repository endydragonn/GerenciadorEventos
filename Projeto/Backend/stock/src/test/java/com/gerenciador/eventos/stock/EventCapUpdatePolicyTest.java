package com.gerenciador.eventos.stock;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.stockclient.api.EventCap;
import com.gerenciador.eventos.stockclient.api.HoldRequest;

/**
 * {@link EventCapRepository#updatePolicy} contra o Postgres real (issues #56 e #47): ao virar de ilimitado para limitado,
 * {@code quant} é {@code capacity - holds held} do evento (o ledger {@code stock.hold}, no lugar de {@code walletevent}), e esses holds
 * ficam marcados {@code decremented = true}, senão o cancelamento deles nunca devolveria a vaga contada no {@code quant}.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class EventCapUpdatePolicyTest extends StockTestSupport {

    @Autowired private HoldService holdService;

    private UUID hold(long evento, String modo) {
        UUID h = UUID.randomUUID();
        holdService.reservar(new HoldRequest(h, novoId(), evento, modo));
        return h;
    }

    /** Evento ilimitado com 2 holds ativos (held) e 1 liberado, que não conta. Devolve os ids dos ativos. */
    private UUID[] ilimitadoComDoisAtivos(long ev) {
        UUID a = hold(ev, HoldRequest.CAS);
        UUID b = hold(ev, HoldRequest.CAS);
        UUID liberado = hold(ev, HoldRequest.CAS);
        holdService.liberar(liberado, ev, 1L);
        return new UUID[] {a, b};
    }

    @Test
    public void ilimitadoParaLimitadoDescontaOsHoldsAtivosEOsMarcaComoDebitados() throws Exception {
        long ev = novoEvento(EventCap.UNLIMITED);
        UUID[] ativos = ilimitadoComDoisAtivos(ev);

        assertTrue(eventCapRepository.updatePolicy(ev, 10, null));

        EventCap cap = eventCapRepository.findByEventId(ev);
        assertEquals(10, cap.getCapacity());
        assertEquals(8, cap.getQuant(), "10 de capacidade - 2 holds ativos (o liberado não conta)");
        assertEquals(Boolean.TRUE, debitado(ativos[0]));
        assertEquals(Boolean.TRUE, debitado(ativos[1]));
        assertInvariante(ev);

        holdService.liberar(ativos[0], ev, 1L);
        assertEquals(9, quant(ev), "o hold de antes da conversão devolve a vaga que a conversão contou");
        assertInvariante(ev);
    }

    @Test
    public void ilimitadoParaLimitadoComCapacidadeIgualAosAtivosZeraAsVagas() {
        long ev = novoEvento(EventCap.UNLIMITED);
        ilimitadoComDoisAtivos(ev);

        eventCapRepository.updatePolicy(ev, 2, null);

        assertEquals(0, quant(ev));
    }

    @Test
    public void ilimitadoParaLimitadoAbaixoDosAtivosERecusadoENadaMuda() throws Exception {
        long ev = novoEvento(EventCap.UNLIMITED);
        UUID[] ativos = ilimitadoComDoisAtivos(ev);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> eventCapRepository.updatePolicy(ev, 1, null));
        assertTrue(ex.getMessage().contains("menor"), ex.getMessage());

        EventCap cap = eventCapRepository.findByEventId(ev);
        assertEquals(EventCap.UNLIMITED, cap.getCapacity());
        assertEquals(EventCap.UNLIMITED, cap.getQuant());
        assertEquals(Boolean.FALSE, debitado(ativos[0]), "recusado: a marca de débito também foi desfeita (mesma transação)");
        assertEquals(Boolean.FALSE, debitado(ativos[1]));
    }

    @Test
    public void ilimitadoSemHoldsParaLimitadoFicaComACapacidadeCheia() {
        long ev = novoEvento(EventCap.UNLIMITED);

        eventCapRepository.updatePolicy(ev, 7, null);

        assertEquals(7, quant(ev));
    }

    @Test
    public void limitadoParaLimitadoAcompanhaAVariacaoDaCapacidade() throws Exception {
        long ev = novoEvento(3);
        hold(ev, HoldRequest.CAS);

        eventCapRepository.updatePolicy(ev, 6, null);
        assertEquals(5, quant(ev), "6 - 1 hold");

        eventCapRepository.updatePolicy(ev, 6, null); // mesma capacidade: nada muda
        assertEquals(5, quant(ev));
        assertInvariante(ev);
    }

    @Test
    public void holdNaiveDeEventoLimitadoNaoViraDebitadoNaLimitadoParaLimitado() throws Exception {
        long ev = novoEvento(3);
        UUID naive = hold(ev, HoldRequest.NAIVE);

        eventCapRepository.updatePolicy(ev, 5, null);

        assertEquals(Boolean.FALSE, debitado(naive), "só a conversão ilimitado -> limitado marca o débito");
        assertEquals(5, quant(ev));
    }

    @Test
    public void limitadoParaIlimitadoViraSentinelaENaoMexeNosHolds() throws Exception {
        long ev = novoEvento(3);
        UUID h = hold(ev, HoldRequest.CAS);

        eventCapRepository.updatePolicy(ev, EventCap.UNLIMITED, null);

        EventCap cap = eventCapRepository.findByEventId(ev);
        assertEquals(EventCap.UNLIMITED, cap.getCapacity());
        assertEquals(EventCap.UNLIMITED, cap.getQuant());
        assertEquals(Boolean.TRUE, debitado(h), "o release de evento ilimitado não toca o eventcap (capacity <> -1)");
        holdService.liberar(h, ev, 1L);
        assertEquals(EventCap.UNLIMITED, quant(ev));
    }

    /** O catalog reenvia a capacidade em todo PUT: ilimitado -> ilimitado com holds ativos não pode "limitar" nem mexer em quant. */
    @Test
    public void ilimitadoParaIlimitadoComHoldsNaoAlteraAPolitica() throws Exception {
        long ev = novoEvento(EventCap.UNLIMITED);
        UUID[] ativos = ilimitadoComDoisAtivos(ev);

        assertTrue(eventCapRepository.updatePolicy(ev, EventCap.UNLIMITED, null));

        EventCap cap = eventCapRepository.findByEventId(ev);
        assertEquals(EventCap.UNLIMITED, cap.getCapacity());
        assertEquals(EventCap.UNLIMITED, cap.getQuant());
        assertEquals(Boolean.FALSE, debitado(ativos[0]));
    }

    /**
     * Um hold de evento ilimitado que comita DEPOIS do snapshot do "marcar" mas antes do "count" não é marcado: se o count o
     * contasse, o quant ficaria 1 abaixo de {@code capacity - held debitados} e o cancelamento dele nunca devolveria a vaga (perda
     * permanente). Simulado sem sleeps: uma 2a conexão trava um dos holds ativos (o "marcar" espera nele, já com snapshot tirado), o
     * hold novo comita nesse intervalo, e a trava cai.
     */
    @Test
    public void holdQueComitaEntreOMarcarEOCountNaoEContado() throws Exception {
        long ev = novoEvento(EventCap.UNLIMITED);
        UUID[] ativos = ilimitadoComDoisAtivos(ev);
        UUID tardio = UUID.randomUUID();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection trava = db.getConnection()) {
            trava.setAutoCommit(false);
            try (Statement st = trava.createStatement()) {
                st.execute("SELECT 1 FROM stock.hold WHERE hold_id = '" + ativos[0] + "' FOR UPDATE");
            }
            Future<Boolean> conversao = pool.submit(() -> eventCapRepository.updatePolicy(ev, 10, null));
            aguardarEsperandoLock(1);
            try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
                st.execute("INSERT INTO stock.hold (hold_id, event_id, user_id, decremented, status) VALUES ('" + tardio + "', "
                        + ev + ", 1, false, 'held')");
            }
            trava.commit();
            assertTrue(conversao.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(Boolean.TRUE, debitado(ativos[0]));
        assertEquals(Boolean.TRUE, debitado(ativos[1]));
        assertEquals(Boolean.FALSE, debitado(tardio), "comitou depois do snapshot do marcar");
        assertEquals(8, quant(ev), "10 - 2 marcados: o tardio não é contado (janela de overselling documentada, nunca vaga perdida)");
        assertInvariante(ev);
    }
}
