package com.gerenciador.eventos;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.FakeReservationServer;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.catalog.EventService;
import com.gerenciador.eventos.catalog.api.Event;
import com.gerenciador.eventos.gateway.EventWalletGateway;
import com.gerenciador.eventos.identity.UserService;
import com.gerenciador.eventos.identity.api.User;
import com.gerenciador.eventos.reservationclient.api.EventCap;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.reservationclient.api.EventCapacityApi;

/**
 * O braço CAS (default) decidido no gateway: UMA chamada HTTP ao serviço reservation (o CAS é atômico lá dentro, sem ler a
 * disponibilidade antes). Também cobre a criação do {@code eventcap} por chamada síncrona do catalog, idempotente por
 * {@code event_id}, e a falha dessa chamada (a criação do evento não pode quebrar: o reservation reconcilia pelo tópico).
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
public class EventWalletGatewayCasTest {

    @Autowired private UserService userService;
    @Autowired private EventService eventService;
    @Autowired private EventWalletGateway gateway;
    @Autowired private EventCapacityApi capacidade;

    @Test
    void casEhUmaChamadaSoPostDaReservaSemLerADisponibilidade() {
        User u = usuario("gw-cas-1");
        Event e = evento(u.getId(), "GW CAS uma chamada", 3);
        FakeReservationServer reservation = FakeReservationServer.get();
        reservation.limparChamadas();

        EventWallet r = gateway.addLink(new EventWallet(u.getId(), e.getEvent_id()), "gw-cas-chave");

        assertEquals("pending", r.getStatus());
        assertEquals("gw-cas-chave", r.getIdempotencyKey(), "a chave volta no header Idempotency-Key");
        assertEquals(List.of("POST /internal/reservations"), reservation.chamadas());
    }

    @Test
    void casLotadoVemDoServicoComAMesmaMensagem() {
        User u1 = usuario("gw-cas-2");
        User u2 = usuario("gw-cas-3");
        Event e = evento(u1.getId(), "GW CAS lotado", 1);
        gateway.addLink(new EventWallet(u1.getId(), e.getEvent_id()), null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> gateway.addLink(new EventWallet(u2.getId(), e.getEvent_id()), null));
        assertEquals("Evento lotado", ex.getMessage());
    }

    /** A validação do endpoint {@code /validate}: carteira + evento (daqui) + "Vínculo já existente" (do serviço). */
    @Test
    void validacaoComponheOsErrosNaMesmaOrdemDeAntes() {
        User u = usuario("gw-cas-4");
        Event e = evento(u.getId(), "GW CAS validacao", 2);

        assertEquals("", gateway.getValidationErrors(new EventWallet(u.getId(), e.getEvent_id())));
        gateway.addLink(new EventWallet(u.getId(), e.getEvent_id()), null);
        assertEquals("Vínculo já existente.", gateway.getValidationErrors(new EventWallet(u.getId(), e.getEvent_id())));
        assertEquals("Carteira não encontrada. Evento não encontrado.",
                gateway.getValidationErrors(new EventWallet(987_654L, 987_655L)));
        assertTrue(gateway.getValidationErrors(new EventWallet(null, 0L)).startsWith("user_id é obrigatório"));
    }

    /**
     * Janela de falha documentada (issue #33, decisão 3): o catalog cria o {@code eventcap} por chamada síncrona DEPOIS de
     * comitar o evento. Se o reservation estiver fora, o evento já existe, a criação NÃO quebra (responde com os valores da
     * requisição) e o {@code eventcap} chega depois pelo {@code EventCreated} do tópico (reconciliação, no serviço). A
     * criação é idempotente por {@code event_id}: repetir não altera a capacidade.
     */
    @Test
    void criarEventoComOReservationForaNaoQuebraEACapacidadeChegaDepoisPelaReconciliacao() {
        User u = usuario("gw-cas-5");
        FakeReservationServer reservation = FakeReservationServer.get();
        reservation.falharCriacaoDeCapacidade(true);
        Event e;
        try {
            e = evento(u.getId(), "GW CAS reservation fora", 5);
        } finally {
            reservation.falharCriacaoDeCapacidade(false);
        }

        assertEquals(5, e.getLot_quantity(), "a resposta traz a capacidade pedida");
        assertEquals(5, e.getQuantity());
        assertEquals(null, capacidade.findByEventId(e.getEvent_id()), "o serviço ainda não tem o eventcap");

        // a reconciliação pelo EventCreated chama a mesma criação idempotente (no serviço real, direto no repositório)
        capacidade.create(new EventCap(e.getEvent_id(), 5, 5, e.getBuy_time_limit()));
        capacidade.create(new EventCap(e.getEvent_id(), 99, 99, null));
        assertEquals(5, capacidade.findByEventId(e.getEvent_id()).getCapacity(), "o eventcap criado primeiro vence");
    }

    /**
     * Editar um evento que ficou SEM eventcap (a criação síncrona falhou e a reconciliação ainda não rodou) não perde a edição:
     * o PATCH responde 404 e o catalog cria o eventcap com os valores NOVOS (a reconciliação, depois, é no-op).
     */
    @Test
    void editarEventoSemEventcapCriaOEventcapComAsCapacidadeNova() {
        User u = usuario("gw-cas-6");
        FakeReservationServer reservation = FakeReservationServer.get();
        reservation.falharCriacaoDeCapacidade(true);
        Event e;
        try {
            e = evento(u.getId(), "GW CAS editar sem eventcap", 5);
        } finally {
            reservation.falharCriacaoDeCapacidade(false);
        }
        assertEquals(null, capacidade.findByEventId(e.getEvent_id()));

        e.setLot_quantity(9);
        eventService.updateEvent(e);

        assertEquals(9, capacidade.findByEventId(e.getEvent_id()).getCapacity(), "a edição não se perdeu");
    }

    /** {@code findByCreatorId} hidrata a lista com UMA chamada em lote, não uma por evento. */
    @Test
    void eventosDoCriadorSaoHidratadosEmUmaChamadaSo() {
        User u = usuario("gw-cas-7");
        evento(u.getId(), "GW CAS criador 1", 2);
        evento(u.getId(), "GW CAS criador 2", 3);
        evento(u.getId(), "GW CAS criador 3", 4);
        FakeReservationServer reservation = FakeReservationServer.get();
        reservation.limparChamadas();

        List<Event> eventos = eventService.findByCreatorId(u.getId());

        assertEquals(3, eventos.size());
        assertEquals(List.of(2, 3, 4), eventos.stream().map(Event::getLot_quantity).sorted().toList());
        assertEquals(List.of("POST /internal/eventcaps/batch"), reservation.chamadas());
    }

    /**
     * Issue #35: o gateway guarda o {@code exists(eventId)} positivo por ~30 s (o catalog vira salto de rede). Apagado o evento,
     * a conferência do gateway ainda o vê dentro da janela (o reservation recusa a reserva depois, sem {@code eventcap}); o
     * negativo nunca é guardado.
     */
    @Test
    void existsDoEventoTemCachePositivo() {
        User u = usuario("gw-cas-8");
        Event e = evento(u.getId(), "GW CAS cache exists", 2);
        assertEquals("", gateway.getValidationErrors(new EventWallet(u.getId(), e.getEvent_id())));

        eventService.delete(e.getEvent_id());

        assertEquals(false, eventService.exists(e.getEvent_id()), "o catalog já não tem o evento");
        assertEquals("", gateway.getValidationErrors(new EventWallet(u.getId(), e.getEvent_id())),
                "positivo em cache: o gateway não reconsulta o catalog dentro da janela");
        assertEquals("Carteira não encontrada. Evento não encontrado.",
                gateway.getValidationErrors(new EventWallet(987_654L, 987_655L)), "negativo não vai para o cache");
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
