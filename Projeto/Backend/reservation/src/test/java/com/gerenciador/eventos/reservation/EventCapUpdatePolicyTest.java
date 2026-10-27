package com.gerenciador.eventos.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.reservationclient.api.EventCap;
import com.gerenciador.eventos.reservationclient.api.EventWallet;

/**
 * {@link EventCapRepository#updatePolicy} contra o Postgres real (issue #56): ao virar de ilimitado para limitado,
 * {@code quant} é {@code capacity - ativas} (reservas {@code pending} + {@code confirmed}), e não a capacidade cheia, que
 * zeraria as inscrições já feitas e permitiria overselling.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class EventCapUpdatePolicyTest extends ReservationTestSupport {

    @Autowired private EventWalletService eventWalletService;

    /** Evento ilimitado com 2 reservas ativas (1 pending, 1 confirmed) e 1 cancelada, que não conta. */
    private Evento ilimitadoComDuasAtivas() {
        Evento e = novoEvento(null, "Politica ilimitado", EventCap.UNLIMITED);
        Usuario pendente = novoUsuario("p");
        Usuario confirmado = novoUsuario("c");
        Usuario cancelado = novoUsuario("x");
        eventWalletService.addLink(new EventWallet(pendente.getId(), e.getEvent_id()));
        eventWalletService.addLink(new EventWallet(confirmado.getId(), e.getEvent_id()));
        eventWalletService.confirm(confirmado.getId(), e.getEvent_id());
        eventWalletService.addLink(new EventWallet(cancelado.getId(), e.getEvent_id()));
        eventWalletService.removeLink(cancelado.getId(), e.getEvent_id());
        return e;
    }

    @Test
    public void ilimitadoParaLimitadoDescontaAsReservasAtivas() {
        Evento e = ilimitadoComDuasAtivas();

        assertTrue(eventCapRepository.updatePolicy(e.getEvent_id(), 10, null));

        EventCap cap = eventCapRepository.findByEventId(e.getEvent_id());
        assertEquals(10, cap.getCapacity());
        assertEquals(8, cap.getQuant(), "10 de capacidade - 2 ativas (a cancelada não conta)");
    }

    @Test
    public void ilimitadoParaLimitadoComCapacidadeIgualAsAtivasZeraAsVagas() {
        Evento e = ilimitadoComDuasAtivas();

        eventCapRepository.updatePolicy(e.getEvent_id(), 2, null);

        assertEquals(0, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());
    }

    @Test
    public void ilimitadoParaLimitadoAbaixoDasAtivasERecusadoENadaMuda() {
        Evento e = ilimitadoComDuasAtivas();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> eventCapRepository.updatePolicy(e.getEvent_id(), 1, null));
        assertTrue(ex.getMessage().contains("menor"), ex.getMessage());

        EventCap cap = eventCapRepository.findByEventId(e.getEvent_id());
        assertEquals(EventCap.UNLIMITED, cap.getCapacity());
        assertEquals(EventCap.UNLIMITED, cap.getQuant());
    }

    @Test
    public void ilimitadoSemReservasParaLimitadoFicaComACapacidadeCheia() {
        Evento e = novoEvento(null, "Politica ilimitado vazio", EventCap.UNLIMITED);

        eventCapRepository.updatePolicy(e.getEvent_id(), 7, null);

        assertEquals(7, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());
    }

    @Test
    public void limitadoParaLimitadoAcompanhaAVariacaoDaCapacidade() {
        Evento e = novoEvento(null, "Politica limitado", 3);
        eventWalletService.addLink(new EventWallet(novoUsuario("a").getId(), e.getEvent_id()));

        eventCapRepository.updatePolicy(e.getEvent_id(), 6, null);
        assertEquals(5, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(), "6 - 1 reserva");

        eventCapRepository.updatePolicy(e.getEvent_id(), 6, null); // mesma capacidade: nada muda
        assertEquals(5, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());
    }

    @Test
    public void limitadoParaIlimitadoViraSentinela() {
        Evento e = novoEvento(null, "Politica limitado->ilimitado", 3);
        eventWalletService.addLink(new EventWallet(novoUsuario("a").getId(), e.getEvent_id()));

        eventCapRepository.updatePolicy(e.getEvent_id(), EventCap.UNLIMITED, null);

        EventCap cap = eventCapRepository.findByEventId(e.getEvent_id());
        assertEquals(EventCap.UNLIMITED, cap.getCapacity());
        assertEquals(EventCap.UNLIMITED, cap.getQuant());
    }

    /** O catalog reenvia a capacidade em todo PUT: ilimitado -> ilimitado com reservas ativas não pode "limitar" nem mexer em quant. */
    @Test
    public void ilimitadoParaIlimitadoComReservasNaoAlteraAPolitica() {
        Evento e = ilimitadoComDuasAtivas();

        assertTrue(eventCapRepository.updatePolicy(e.getEvent_id(), EventCap.UNLIMITED, null));

        EventCap cap = eventCapRepository.findByEventId(e.getEvent_id());
        assertEquals(EventCap.UNLIMITED, cap.getCapacity());
        assertEquals(EventCap.UNLIMITED, cap.getQuant());
    }
}
