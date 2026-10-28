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

import com.gerenciador.eventos.TestSupport.FakeCatalogServer;
import com.gerenciador.eventos.TestSupport.FakeReservationServer;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.catalogclient.api.CatalogUnavailableException;
import com.gerenciador.eventos.catalogclient.api.Event;
import com.gerenciador.eventos.catalogclient.api.EventApi;
import com.gerenciador.eventos.gateway.EventWalletGateway;
import com.gerenciador.eventos.identityclient.api.UserApi;
import com.gerenciador.eventos.identityclient.api.User;
import com.gerenciador.eventos.reservationclient.api.EventWallet;

/**
 * O braço CAS (default) decidido no gateway: UMA chamada HTTP ao serviço reservation (o CAS é atômico lá dentro, sem ler a
 * disponibilidade antes). Desde a #35 o evento vem do serviço catalog (aqui o {@code FakeCatalogServer}, pelo
 * {@code CatalogClient} real): a criação do {@code eventcap} e a hidratação em lote são do catalog e são testadas no módulo dele
 * ({@code catalog/EventTest}).
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
public class EventWalletGatewayCasTest {

    @Autowired private UserApi userService;
    @Autowired private EventApi eventService;
    @Autowired private EventWalletGateway gateway;

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

    /** A validação do endpoint {@code /validate}: evento (daqui; a carteira não é mais conferida, #36) + "Vínculo já existente" (do serviço). */
    @Test
    void validacaoComponheOsErrosNaMesmaOrdemDeAntes() {
        User u = usuario("gw-cas-4");
        Event e = evento(u.getId(), "GW CAS validacao", 2);

        assertEquals("", gateway.getValidationErrors(new EventWallet(u.getId(), e.getEvent_id())));
        gateway.addLink(new EventWallet(u.getId(), e.getEvent_id()), null);
        assertEquals("Vínculo já existente.", gateway.getValidationErrors(new EventWallet(u.getId(), e.getEvent_id())));
        assertEquals("Evento não encontrado.",
                gateway.getValidationErrors(new EventWallet(987_654L, 987_655L)));
        assertTrue(gateway.getValidationErrors(new EventWallet(null, 0L)).startsWith("user_id é obrigatório"));
    }

    /**
     * Issue #35: o gateway guarda o {@code exists(eventId)} positivo por ~30 s (o catalog é um salto de rede). Apagado o evento,
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

        // negativo NÃO vai para o cache: consulta um id inexistente, o catalog passa a ter um evento com ESSE id e ele aparece
        long idNovo = 2_000_000_000L + (System.nanoTime() % 100_000_000L);
        assertEquals("Evento não encontrado.", gateway.getValidationErrors(new EventWallet(u.getId(), idNovo)));
        FakeCatalogServer catalog = FakeCatalogServer.get();
        catalog.gravarComId(idNovo, u.getId(), "GW CAS cache negativo " + idNovo);
        try {
            assertEquals("", gateway.getValidationErrors(new EventWallet(u.getId(), idNovo)),
                    "o 'não existe' anterior não ficou em cache: o evento recém-criado já é visto");
        } finally {
            catalog.apagar(idNovo);
        }
    }

    /**
     * Caso 6 do spec (parte do gateway): com o catalog fora, a conferência do evento na reserva FALHA com erro
     * ({@link CatalogUnavailableException}, 503 no {@code /bff}) e NUNCA vira "Evento não encontrado"; nada chega ao reservation.
     * O id nunca foi conferido antes (o cache positivo não mascara a falha).
     */
    @Test
    void catalogForaNaReservaFalhaComErroENuncaComEventoNaoEncontrado() {
        User u = usuario("gw-cas-9");
        long nuncaConferido = 1_900_000_000L + (System.nanoTime() % 100_000_000L);
        FakeReservationServer reservation = FakeReservationServer.get();
        reservation.limparChamadas();
        FakeCatalogServer catalog = FakeCatalogServer.get();
        catalog.foraDoAr(true);
        try {
            assertThrows(CatalogUnavailableException.class,
                    () -> gateway.addLink(new EventWallet(u.getId(), nuncaConferido), null));
            assertThrows(CatalogUnavailableException.class,
                    () -> gateway.getValidationErrors(new EventWallet(u.getId(), nuncaConferido)));
        } finally {
            catalog.foraDoAr(false);
        }
        assertEquals(List.of(), reservation.chamadas(), "a reserva não foi tentada");
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
