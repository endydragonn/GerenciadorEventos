package com.gerenciador.eventos;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.FakeReservationServer;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.catalogclient.api.EventApi;
import com.gerenciador.eventos.catalogclient.api.Event;
import com.gerenciador.eventos.gateway.EventWalletGateway;
import com.gerenciador.eventos.identity.UserService;
import com.gerenciador.eventos.identityclient.api.User;
import com.gerenciador.eventos.reservationclient.api.EventWallet;

/**
 * O braço NAIVE decidido no gateway ({@code reservation.readpath=naive}, issue #33): a reserva custa DUAS chamadas HTTP ao
 * serviço reservation, na ordem GET da disponibilidade e DEPOIS POST da reserva sem CAS. A ida e volta entre elas é a janela
 * check-then-act do braço de controle do experimento. O serviço aqui é o servidor HTTP falso do monólito
 * ({@code FakeReservationServer}); o mesmo cliente contra o serviço real está em {@code ReservationApiE2ETest}
 * (módulo {@code reservation}).
 */
@SpringBootTest(properties = "reservation.readpath=naive")
@Import(GlobalDbTruncator.class)
public class EventWalletGatewayNaiveTest {

    @Autowired private UserService userService;
    @Autowired private EventApi eventService;
    @Autowired private EventWalletGateway gateway;

    private FakeReservationServer reservation;

    @BeforeEach
    void limparChamadas() {
        reservation = FakeReservationServer.get();
    }

    @Test
    void naiveFazDuasChamadasGetDaDisponibilidadeEDepoisPostDaReserva() {
        User u = usuario("gw-naive-1");
        Event e = evento(u.getId(), "GW Naive duas chamadas", 3);
        reservation.limparChamadas();

        EventWallet r = gateway.addLink(new EventWallet(u.getId(), e.getEvent_id()), null);

        assertEquals("pending", r.getStatus());
        assertEquals(List.of("GET /internal/events/" + e.getEvent_id() + "/availability",
                "POST /internal/reservations"), reservation.chamadas(), "duas chamadas HTTP, nessa ordem");
    }

    @Test
    void naiveComDisponibilidadeZeroRejeitaNaPrimeiraChamadaSemFazerAReserva() {
        User u = usuario("gw-naive-2");
        Event e = evento(u.getId(), "GW Naive lotado", 1);
        reservation.projetar(e.getEvent_id(), 1); // o "consumer" já aplicou a reserva
        reservation.limparChamadas();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> gateway.addLink(new EventWallet(u.getId(), e.getEvent_id()), null));

        assertEquals("Evento lotado", ex.getMessage());
        assertEquals(List.of("GET /internal/events/" + e.getEvent_id() + "/availability"), reservation.chamadas());
    }

    /** A janela: com a projeção parada, dois pedidos leem "1 vaga" e os dois reservam; o naive oversella. */
    @Test
    void naiveOversellaQuandoAProjecaoEstaDefasada() {
        User u1 = usuario("gw-naive-3");
        User u2 = usuario("gw-naive-4");
        Event e = evento(u1.getId(), "GW Naive oversell", 1);

        gateway.addLink(new EventWallet(u1.getId(), e.getEvent_id()), null);
        gateway.addLink(new EventWallet(u2.getId(), e.getEvent_id()), null);

        assertEquals(2, reservation.reservasAtivas(e.getEvent_id()), "2 reservas para 1 vaga: overselling do braço de controle");
    }

    /** Carteira e evento são conferidos NO monólito, antes de qualquer chamada ao serviço. */
    @Test
    void carteiraEEventoInexistentesNaoChegamAoServico() {
        User u = usuario("gw-naive-5");
        Event e = evento(u.getId(), "GW Naive pre-checagem", 2);
        reservation.limparChamadas();

        assertEquals("Carteira do usuário não encontrada",
                assertThrows(IllegalArgumentException.class,
                        () -> gateway.addLink(new EventWallet(987_654L, e.getEvent_id()), null)).getMessage());
        assertEquals("Evento não encontrado",
                assertThrows(IllegalArgumentException.class,
                        () -> gateway.addLink(new EventWallet(u.getId(), 987_654L), null)).getMessage());
        assertTrue(reservation.chamadas().isEmpty(), "nada foi chamado: " + reservation.chamadas());
    }

    private User usuario(String prefixo) {
        User u = new User();
        u.setName(prefixo);
        u.setEmail(prefixo + "@example.com");
        u.setFone("gw-" + prefixo);
        return userService.createUser(u);
    }

    private Event evento(Long criador, String nome, int capacidade) {
        Event e = new Event();
        e.setCreator_id(criador);
        e.setEvent_name(nome);
        e.setIs_EAD(true);
        e.setEvent_date(LocalDateTime.now().plusDays(10));
        e.setLot_quantity(capacidade);
        e.setDescription("desc");
        return eventService.createEvent(e);
    }
}
