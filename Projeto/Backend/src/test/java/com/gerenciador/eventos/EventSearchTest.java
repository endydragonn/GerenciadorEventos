package com.gerenciador.eventos;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.POJO.Event;
import com.gerenciador.eventos.POJO.User;
import com.gerenciador.eventos.Service.EventService;
import com.gerenciador.eventos.Service.UserService;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;

/**
 * Issue #109: searchEvents filtra no banco (LIKE escapado, case-insensitive) e hidrata em lote.
 * O banco é compartilhado com outras classes de teste, então cada teste usa um marcador único
 * no nome e filtra o resultado por ele.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class EventSearchTest {

    @Autowired
    private UserService userService;

    @Autowired
    private EventService eventService;

    private Long creatorId;

    @BeforeAll
    void createCreator() {
        User u = new User();
        u.setName("Creator Search");
        u.setEmail("creator-search-" + UUID.randomUUID() + "@example.com");
        u.setFone("11955555555");
        creatorId = userService.createUser(u).getId();
    }

    private Event newEvent(String name, int capacity, List<String> presenters) {
        Event e = new Event();
        e.setCreator_id(creatorId);
        e.setEvent_name(name);
        e.setIs_EAD(true);
        e.setEvent_date(LocalDateTime.now().plusDays(30).withNano(0));
        e.setLot_quantity(capacity);
        e.setDescription("desc");
        e.setPresenters(presenters);
        return eventService.createEvent(e);
    }

    private String marker() {
        return "Mk" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    public void matchIsCaseInsensitive() {
        String mk = marker();
        Event a = newEvent("Alpha " + mk, 10, null);
        newEvent("Beta " + marker(), 10, null);

        List<Event> r = eventService.searchEvents(mk.toUpperCase());
        assertEquals(1, r.size());
        assertEquals(a.getEvent_id(), r.get(0).getEvent_id());
        assertEquals(1, eventService.searchEvents(mk.toLowerCase()).size());
    }

    @Test
    public void noMatchReturnsEmpty() {
        assertTrue(eventService.searchEvents("zzz-" + marker()).isEmpty());
    }

    @Test
    public void emptyOrBlankTermReturnsAllInIdOrder() {
        Event a = newEvent("All1 " + marker(), 5, null);
        Event b = newEvent("All2 " + marker(), 5, null);
        for (String t : new String[] {null, "", "   "}) {
            List<Event> r = eventService.searchEvents(t);
            List<Long> ids = r.stream().map(Event::getEvent_id).toList();
            assertTrue(ids.contains(a.getEvent_id()) && ids.contains(b.getEvent_id()), "termo=[" + t + "]");
            assertTrue(ids.indexOf(a.getEvent_id()) < ids.indexOf(b.getEvent_id()));
            assertEquals(ids.stream().sorted().toList(), ids, "ordenado por event_id");
        }
    }

    @Test
    public void wildcardsAreLiteral() {
        String mk = marker();
        Event pct = newEvent("Pct " + mk + " 100%", 5, null);
        Event under = newEvent("Und " + mk + " a_b", 5, null);
        Event back = newEvent("Bck " + mk + " c\\d", 5, null);
        newEvent("Oth " + mk + " 100x aXb cXd", 5, null);

        assertEquals(List.of(pct.getEvent_id()),
                eventService.searchEvents(mk + " 100%").stream().map(Event::getEvent_id).toList());
        assertEquals(List.of(under.getEvent_id()),
                eventService.searchEvents("a_b").stream().filter(e -> e.getEvent_name().contains(mk))
                        .map(Event::getEvent_id).toList());
        assertEquals(List.of(back.getEvent_id()),
                eventService.searchEvents("c\\d").stream().filter(e -> e.getEvent_name().contains(mk))
                        .map(Event::getEvent_id).toList());
        // '%' sozinho não casa tudo: só quem tem '%' literal
        assertTrue(eventService.searchEvents("%").stream().allMatch(e -> e.getEvent_name().contains("%")));
    }

    @Test
    public void searchHydratesLikeFindById() {
        String mk = marker();
        Event with = newEvent("Hyd1 " + mk, 20, List.of("Zeca", "Ana"));
        Event without = newEvent("Hyd2 " + mk, -1, null);

        List<Event> r = eventService.searchEvents(mk);
        assertEquals(2, r.size());
        for (Event found : r) {
            Event expected = eventService.findById(found.getEvent_id());
            assertEquals(expected.getLot_quantity(), found.getLot_quantity());
            assertEquals(expected.getQuantity(), found.getQuantity());
            assertEquals(expected.getBuy_time_limit(), found.getBuy_time_limit());
            assertEquals(expected.getPresenters(), found.getPresenters());
        }
        Event f1 = r.stream().filter(e -> e.getEvent_id().equals(with.getEvent_id())).findFirst().orElseThrow();
        assertEquals(20, f1.getLot_quantity());
        assertEquals(List.of("Ana", "Zeca"), f1.getPresenters());
        Event f2 = r.stream().filter(e -> e.getEvent_id().equals(without.getEvent_id())).findFirst().orElseThrow();
        assertEquals(-1, f2.getLot_quantity());
        assertTrue(f2.getPresenters() != null && f2.getPresenters().isEmpty());
    }
}
