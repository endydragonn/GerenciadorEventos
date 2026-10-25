package com.gerenciador.eventos.catalog;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.FakeEventCapServer;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.catalogclient.api.Event;
import com.gerenciador.eventos.catalogclient.api.EventoNaoEncontradoException;

/**
 * {@code EventService} + repositories do SERVIÇO catalog contra o schema {@code catalog} (cópia adaptada do {@code EventTest} do
 * monólito, issue #35): o criador é só um id (sem FK para {@code users}); a capacidade vem do reservation falso.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class EventTest {

    @Autowired
    private EventService eventService;

    private Event evento(long criador, String nome, int capacidade) {
        Event e = new Event();
        e.setCreator_id(criador);
        e.setEvent_name(nome);
        e.setIs_EAD(true);
        e.setEvent_date(LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS));
        e.setLot_quantity(capacidade);
        e.setDescription("desc");
        return e;
    }

    private static String unico(String prefixo) {
        return prefixo + " " + System.nanoTime();
    }

    @Test
    public void testCreateEventWithDefaults() {
        // trunca a segundos: buy_time_limit assume event_date e volta do eventcap
        LocalDateTime eventDate = LocalDateTime.now().plusDays(30).truncatedTo(ChronoUnit.SECONDS);
        Event e = evento(1L, unico("Evento X"), 50);
        e.setEvent_date(eventDate);
        e.setBuy_time_limit(null); // default aplicado no service

        Event saved = eventService.createEvent(e);
        assertNotNull(saved.getEvent_id());
        assertEquals(eventDate, saved.getEvent_date());
        assertEquals(eventDate, saved.getBuy_time_limit(), "buy_time_limit deve assumir event_date quando nulo");
        assertEquals(50, saved.getLot_quantity(), "capacidade deve vir do eventcap");
        assertEquals(50, saved.getQuantity(), "quant nasce igual à capacidade (todas as vagas disponíveis)");
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        Event reloaded = eventService.findById(saved.getEvent_id());
        assertEquals(50, reloaded.getLot_quantity());
        assertEquals(50, reloaded.getQuantity());
    }

    @Test
    public void testBuyTimeLimitDefaultsToEventDateOnUpdate() {
        Event e = evento(1L, unico("Evento BTL"), 10);
        e.setEvent_date(LocalDateTime.now().plusDays(20));
        e.setBuy_time_limit(LocalDateTime.now().plusDays(15));
        Event saved = eventService.createEvent(e);

        LocalDateTime newDate = LocalDateTime.now().plusDays(25).truncatedTo(ChronoUnit.SECONDS);
        saved.setEvent_date(newDate);
        saved.setBuy_time_limit(null);
        Event updated = eventService.updateEvent(saved);

        assertEquals(newDate, updated.getBuy_time_limit());
    }

    /** A imagem tem métodos próprios; as listas não carregam {@code image_data}; {@code updateEvent} não mexe na imagem. */
    @Test
    public void imagemEmMetodosPropriosForaDasListasEDoUpdate() {
        long criador = 7_000L + (System.nanoTime() % 1000);
        String nome = unico("Evento Img");
        Event saved = eventService.createEvent(evento(criador, nome, 5));
        assertNull(eventService.findImage(saved.getEvent_id()), "sem imagem ainda");

        eventService.saveImage(saved.getEvent_id(), new byte[] {4, 5, 6});
        assertArrayEquals(new byte[] {4, 5, 6}, eventService.findImage(saved.getEvent_id()));
        assertArrayEquals(new byte[] {4, 5, 6}, eventService.findById(saved.getEvent_id()).getImage_data());
        assertNull(eventService.searchEvents(nome).get(0).getImage_data(), "busca sem image_data");
        assertNull(eventService.findByCreatorId(criador).get(0).getImage_data(), "lista do criador sem image_data");

        Event semImagem = eventService.findById(saved.getEvent_id());
        semImagem.setImage_data(null);
        semImagem.setDescription("editado");
        eventService.updateEvent(semImagem);
        assertArrayEquals(new byte[] {4, 5, 6}, eventService.findImage(saved.getEvent_id()), "o update não apaga a imagem");

        assertThrows(EventoNaoEncontradoException.class, () -> eventService.saveImage(999_999L, new byte[] {1}));
        assertNull(eventService.findImage(999_999L));
        assertTrue(eventService.exists(saved.getEvent_id()));
        assertFalse(eventService.exists(999_999L));
    }

    /** {@code findCreatorId}: só o criador, {@code null} para evento inexistente (checagem de dono do gateway). */
    @Test
    public void findCreatorIdDevolveSoOCriador() {
        Event saved = eventService.createEvent(evento(31L, unico("Evento Dono"), 2));
        assertEquals(31L, eventService.findCreatorId(saved.getEvent_id()));
        assertNull(eventService.findCreatorId(999_999L));
    }

    /** {@code findByCreatorId} filtra no banco: só os eventos do criador, em ordem de {@code event_id}. */
    @Test
    public void findByCreatorIdSoDevolveOsDoCriador() {
        long a = 8_000L + (System.nanoTime() % 1000);
        long b = a + 1000;
        String sufixo = " " + System.nanoTime();
        for (String nome : List.of("FBC A1", "FBC B1", "FBC A2")) {
            eventService.createEvent(evento(nome.contains(" A") ? a : b, nome + sufixo, 3));
        }

        assertEquals(List.of("FBC A1" + sufixo, "FBC A2" + sufixo),
                eventService.findByCreatorId(a).stream().map(Event::getEvent_name).toList());
        assertEquals(List.of(), eventService.findByCreatorId(999_999L));
    }

    /**
     * Janela de falha documentada (issue #33, decisão 3; veio do {@code EventWalletGatewayCasTest} do monólito na #35): o
     * {@code eventcap} nasce por chamada síncrona DEPOIS do commit do evento. Com o reservation fora, a criação NÃO quebra
     * (responde com os valores da requisição) e o {@code eventcap} chega depois pela reconciliação (no serviço reservation).
     */
    @Test
    public void criarEventoComOReservationForaNaoQuebra() {
        FakeEventCapServer reservation = FakeEventCapServer.get();
        reservation.falharCriacao(true);
        Event saved;
        try {
            saved = eventService.createEvent(evento(41L, unico("Evento reservation fora"), 5));
        } finally {
            reservation.falharCriacao(false);
        }
        assertEquals(5, saved.getLot_quantity(), "a resposta traz a capacidade pedida");
        assertEquals(5, saved.getQuantity());
        assertNull(reservation.capacidade(saved.getEvent_id()), "o reservation ainda não tem o eventcap");
        assertTrue(eventService.exists(saved.getEvent_id()), "o evento foi gravado");
    }

    /**
     * Editar um evento que ficou SEM eventcap não perde a edição: o PATCH responde 404 e o catalog cria o eventcap com os valores
     * NOVOS (a reconciliação, depois, é no-op).
     */
    @Test
    public void editarEventoSemEventcapCriaOEventcapComACapacidadeNova() {
        FakeEventCapServer reservation = FakeEventCapServer.get();
        reservation.falharCriacao(true);
        Event saved;
        try {
            saved = eventService.createEvent(evento(42L, unico("Evento editar sem eventcap"), 5));
        } finally {
            reservation.falharCriacao(false);
        }
        assertNull(reservation.capacidade(saved.getEvent_id()));

        saved.setLot_quantity(9);
        eventService.updateEvent(saved);

        assertEquals(9, reservation.capacidade(saved.getEvent_id()).getCapacity(), "a edição não se perdeu");
    }

    /** {@code findByCreatorId} hidrata a lista com UMA chamada em lote ao reservation, não uma por evento. */
    @Test
    public void eventosDoCriadorSaoHidratadosEmUmaChamadaSo() {
        long criador = 9_000L + (System.nanoTime() % 1000);
        for (int capacidade = 2; capacidade <= 4; capacidade++) {
            eventService.createEvent(evento(criador, unico("Evento lote " + capacidade), capacidade));
        }
        FakeEventCapServer reservation = FakeEventCapServer.get();
        reservation.limparChamadas();

        List<Event> eventos = eventService.findByCreatorId(criador);

        assertEquals(List.of(2, 3, 4), eventos.stream().map(Event::getLot_quantity).sorted().toList());
        assertEquals(List.of("POST /internal/eventcaps/batch"), reservation.chamadas());
    }

    @Test
    public void testCapacityIsRequired() {
        Event e = evento(1L, unico("Sem Capacidade"), 1);
        e.setLot_quantity(null);
        assertThrows(IllegalArgumentException.class, () -> eventService.createEvent(e));
    }

    @Test
    public void findByIdDeInexistenteLancaNaoEncontrado() {
        EventoNaoEncontradoException ex = assertThrows(EventoNaoEncontradoException.class, () -> eventService.findById(999_999L));
        assertTrue(ex.getMessage().startsWith("Evento não encontrado"), ex.getMessage());
    }
}
