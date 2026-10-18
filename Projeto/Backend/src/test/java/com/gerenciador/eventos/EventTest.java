package com.gerenciador.eventos;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.catalog.api.Event;
import com.gerenciador.eventos.identity.api.User;
import com.gerenciador.eventos.catalog.EventService;
import com.gerenciador.eventos.identity.UserService;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class EventTest {

    @Autowired
    private UserService userService;

    @Autowired
    private EventService eventService;

    // Limpeza suite-level via GlobalDbTruncator

    @Test
    public void testCreateEventWithDefaults() {
        // 1) Criar usuário criador
        User creator = new User();
        creator.setName("Creator");
        creator.setEmail("creator@example.com");
        creator.setFone("11922222222");
        creator = userService.createUser(creator);
        assertNotNull(creator.getId());

        // 2) Criar evento com buy_time_limit nulo -> deve assumir event_date;
        //    capacidade (lot_quantity) agora é obrigatória e vira o agregado eventcap.
        Event e = new Event();
        e.setCreator_id(creator.getId());
        e.setEvent_name("Evento X");
        e.setIs_EAD(true); // EAD, logo address opcional
        // trunca a segundos: buy_time_limit assume event_date e é persistido em
        // eventcap.buy_time_limit (timestamp = micros no Postgres); sem truncar, a
        // reidratação volta com precisão menor que os nanos de LocalDateTime.now().
        // Mesmo padrão do teste-irmão testBuyTimeLimitDefaultsToEventDateOnUpdate.
        LocalDateTime eventDate = LocalDateTime.now().plusDays(30)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        e.setEvent_date(eventDate);
        e.setBuy_time_limit(null); // default aplicado no service
        e.setLot_quantity(50);     // capacidade
        e.setDescription("desc");

        Event saved = eventService.createEvent(e);
        assertNotNull(saved.getEvent_id());
        assertEquals(eventDate, saved.getEvent_date());
        assertEquals(eventDate, saved.getBuy_time_limit(), "buy_time_limit deve assumir event_date quando nulo");
        assertEquals(50, saved.getLot_quantity(), "capacidade deve vir do eventcap");
        assertEquals(50, saved.getQuantity(), "quant nasce igual à capacidade (todas as vagas disponíveis)");
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        // 3) Recarregar e conferir que a hidratação lê o agregado
        Event reloaded = eventService.findById(saved.getEvent_id());
        assertEquals(50, reloaded.getLot_quantity());
        assertEquals(50, reloaded.getQuantity());
    }

    @Test
    public void testBuyTimeLimitDefaultsToEventDateOnUpdate() {
        User creator = new User();
        creator.setName("Creator BTL");
        creator.setEmail("creator-btl@example.com");
        creator.setFone("11944444444");
        creator = userService.createUser(creator);

        Event e = new Event();
        e.setCreator_id(creator.getId());
        e.setEvent_name("Evento BTL");
        e.setIs_EAD(true);
        e.setEvent_date(LocalDateTime.now().plusDays(20));
        e.setBuy_time_limit(LocalDateTime.now().plusDays(15));
        e.setLot_quantity(10);
        e.setDescription("desc");
        Event saved = eventService.createEvent(e);

        // atualiza sem informar buy_time_limit -> deve virar a data do evento
        LocalDateTime newDate = LocalDateTime.now().plusDays(25)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        saved.setEvent_date(newDate);
        saved.setBuy_time_limit(null);
        Event updated = eventService.updateEvent(saved);

        assertEquals(newDate, updated.getBuy_time_limit());
    }

    @Test
    public void testCapacityIsRequired() {
        User creator = new User();
        creator.setName("Creator2");
        creator.setEmail("creator2@example.com");
        creator.setFone("11933333333");
        creator = userService.createUser(creator);

        Event e = new Event();
        e.setCreator_id(creator.getId());
        e.setEvent_name("Sem Capacidade");
        e.setIs_EAD(true);
        e.setEvent_date(LocalDateTime.now().plusDays(30));
        e.setDescription("desc");
        e.setLot_quantity(null);

        assertThrows(IllegalArgumentException.class, () -> eventService.createEvent(e));
    }
}
