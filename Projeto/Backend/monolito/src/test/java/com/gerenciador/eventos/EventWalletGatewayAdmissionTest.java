package com.gerenciador.eventos;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.FakeAdmissionServer;
import com.gerenciador.eventos.admissionclient.api.AdmissionRecusadaException;
import com.gerenciador.eventos.admissionclient.api.AdmissionUnavailableException;
import com.gerenciador.eventos.gateway.EstadoDaReserva;
import com.gerenciador.eventos.gateway.ReservaEnfileiradaException;
import com.gerenciador.eventos.TestSupport.FakeReservationServer;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.catalogclient.api.Event;
import com.gerenciador.eventos.catalogclient.api.EventApi;
import com.gerenciador.eventos.gateway.EventWalletGateway;
import com.gerenciador.eventos.identityclient.api.User;
import com.gerenciador.eventos.identityclient.api.UserApi;
import com.gerenciador.eventos.reservationclient.api.EventWallet;

/**
 * O gateway conta cada tentativa de reserva no serviço admission (issue #41), aqui o servidor HTTP falso do monólito
 * ({@code FakeAdmissionServer}) pelo {@code AdmissionClient} real: UMA chamada por {@code addLink}, DEPOIS das validações e ANTES
 * do reservation; o UUID que o gateway gera quando falta a chave vai SÓ ao admission (o reservation recebe a chave do cliente, ou
 * nenhuma: o caminho síncrono é idêntico ao de antes da #41); fail-open quando o admission falha e disjuntor depois de 5 falhas seguidas. O braço {@code naive} é coberto em {@code EventWalletGatewayNaiveTest}; o contador de verdade (Redis), no módulo
 * {@code admission}.
 */
@SpringBootTest(properties = "admission.client.open-ms=400") // circuito aberto por 400 ms (e não 5 s) para o teste do disjuntor
@Import(GlobalDbTruncator.class)
public class EventWalletGatewayAdmissionTest {

    private static final String POST_RESERVA = "POST /internal/reservations";

    @Autowired private UserApi userService;
    @Autowired private EventApi eventService;
    @Autowired private EventWalletGateway gateway;

    @BeforeEach
    void zerarAdmissionFalso() {
        FakeAdmissionServer.get().reset(); // chamadas, gancho e falha injetada de um teste não vazam para o outro
    }

    @AfterEach
    void deixarOAdmissionFalsoLimpo() {
        FakeAdmissionServer.get().reset(); // singleton da JVM: modoFila/falhar de um teste não pode vazar para as classes seguintes
    }

    private static long postsDeReserva() {
        return FakeReservationServer.get().chamadas().stream().filter(POST_RESERVA::equals).count();
    }

    @Test
    void contaUmaVezAntesDoReservationComAChaveDoCliente() {
        User u = usuario("gw-adm-1");
        Event e = evento(u.getId(), "GW Admission conta", 3);
        FakeAdmissionServer admission = FakeAdmissionServer.get();
        FakeReservationServer.get().limparChamadas();
        AtomicLong reservasQuandoContou = new AtomicLong(-1);
        admission.aoReceber(c -> reservasQuandoContou.set(postsDeReserva()));

        EventWallet r = gateway.addLink(new EventWallet(u.getId(), e.getEvent_id()), "gw-adm-chave-1");

        assertEquals("pending", r.getStatus());
        assertEquals(List.of(new FakeAdmissionServer.Chamada(e.getEvent_id(), u.getId(), "gw-adm-chave-1", "cas")),
                admission.chamadas(), "UMA chamada, com evento, usuário, a chave e o braço");
        assertEquals(0, reservasQuandoContou.get(), "o admission foi chamado ANTES do reservation");
        assertEquals(1, postsDeReserva(), "e o reservation foi chamado depois");
        assertEquals(List.of("gw-adm-chave-1"), FakeReservationServer.get().chavesDeReserva(),
                "com chave do cliente, admission e reservation recebem a MESMA");
        assertTrue(admission.recusadasPorToken().isEmpty(), "o cliente envia o token interno");
    }

    @Test
    void semChaveOUuidGeradoVaiSoParaOAdmissionEOReservationRecebeSemHeader() {
        User u = usuario("gw-adm-2");
        Event e = evento(u.getId(), "GW Admission chave gerada", 3);
        FakeAdmissionServer admission = FakeAdmissionServer.get();
        FakeReservationServer.get().limparChamadas();

        EventWallet r = gateway.addLink(new EventWallet(u.getId(), e.getEvent_id()), null);

        assertEquals(1, admission.chamadas().size());
        UUID.fromString(admission.chamadas().get(0).idempotencyKey()); // o admission exige uma chave: o gateway gerou um UUID
        assertEquals(java.util.Arrays.asList((String) null), FakeReservationServer.get().chavesDeReserva(),
                "o reservation recebe a requisição SEM o header Idempotency-Key: o caminho síncrono é o de antes da #41");
        assertEquals("pending", r.getStatus());
    }

    @Test
    void tentativaInvalidaNaoContaNemChegaAoReservation() {
        User u = usuario("gw-adm-3");
        FakeReservationServer.get().limparChamadas();
        FakeAdmissionServer admission = FakeAdmissionServer.get();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> gateway.addLink(new EventWallet(u.getId(), 987_654L), null));
        assertEquals("Evento não encontrado", ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> gateway.addLink(new EventWallet(u.getId(), 0L), null));
        assertThrows(IllegalArgumentException.class, () -> gateway.addLink(new EventWallet(null, 1L), null));

        assertEquals(List.of(), admission.chamadas(), "validação do gateway barra antes de contar");
        assertEquals(0, postsDeReserva());
    }

    @Test
    void chaveLongaNaoFalhaNoAdmissionEODaoOReservationDa400DeSempre() {
        User u = usuario("gw-adm-4");
        Event e = evento(u.getId(), "GW Admission chave longa", 3);
        FakeReservationServer.get().limparChamadas();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> gateway.addLink(new EventWallet(u.getId(), e.getEvent_id()), "x".repeat(256)));

        assertEquals("Idempotency-Key deve ter no máximo 255 caracteres", ex.getMessage());
        assertEquals(1, postsDeReserva(), "o 400 do admission (chave longa) é ignorado: quem recusa é o reservation, como sempre");
    }

    @Test
    void admissionComFalhaFazFailOpenEAReservaSegueSincrona() {
        User u = usuario("gw-adm-5");
        User u2 = usuario("gw-adm-6");
        Event e = evento(u.getId(), "GW Admission fail-open", 5);
        FakeAdmissionServer admission = FakeAdmissionServer.get();
        FakeReservationServer.get().limparChamadas();
        admission.falhar(503);
        try {
            EventWallet r1 = gateway.addLink(new EventWallet(u.getId(), e.getEvent_id()), "gw-adm-aberto-1");
            EventWallet r2 = gateway.addLink(new EventWallet(u2.getId(), e.getEvent_id()), null);

            assertEquals("pending", r1.getStatus());
            assertEquals("pending", r2.getStatus());
            assertEquals(2, postsDeReserva(), "as duas reservas foram feitas apesar do admission fora");
        } finally {
            admission.falhar(0); // 2 falhas < 5: o disjuntor não abriu; o próximo sucesso zera a contagem
        }
        User u3 = usuario("gw-adm-7");
        gateway.addLink(new EventWallet(u3.getId(), e.getEvent_id()), "gw-adm-volta");
        assertEquals(1, admission.chamadas().size(), "admission de volta: a chamada seguinte já é contada");
    }

    /**
     * 5 falhas seguidas abrem o disjuntor do gateway: a 6ª e a 7ª reserva já não chamam o admission (nenhuma requisição chega a ele), e todas
     * as 7 reservas foram feitas. Passado o tempo (400 ms aqui) a próxima chamada testa o serviço e, com ele de volta, fecha o circuito.
     */
    @Test
    void cincoFalhasSeguidasAbremODisjuntorEOGatewayParaDeChamarOAdmission() throws InterruptedException {
        Event e = evento(usuario("gw-adm-dono").getId(), "GW Admission disjuntor", 20);
        FakeAdmissionServer admission = FakeAdmissionServer.get();
        FakeReservationServer.get().limparChamadas();
        admission.falhar(503);
        try {
            for (int i = 0; i < 7; i++) {
                User u = usuario("gw-adm-disj-" + i);
                assertEquals("pending", gateway.addLink(new EventWallet(u.getId(), e.getEvent_id()), null).getStatus());
            }
            assertEquals(5, admission.recebidas(), "só as 5 primeiras chegaram ao admission; com o circuito aberto nenhuma chamada sai");
            assertEquals(7, postsDeReserva(), "as 7 reservas foram feitas apesar do admission fora");
        } finally {
            admission.falhar(0);
        }
        Thread.sleep(600); // passa o tempo do circuito aberto: a próxima chamada é a sonda
        gateway.addLink(new EventWallet(usuario("gw-adm-disj-volta").getId(), e.getEvent_id()), null);
        gateway.addLink(new EventWallet(usuario("gw-adm-disj-volta2").getId(), e.getEvent_id()), null);
        assertEquals(2, admission.chamadas().size(), "admission de volta: a sonda fechou o circuito e as chamadas seguintes são contadas");
    }

    // ======================= modo fila (issue #43) =======================

    @Test
    void modoFilaLancaReservaEnfileiradaESemChamarOReservation() {
        User u = usuario("gw-adm-fila-1");
        Event e = evento(u.getId(), "GW Admission fila", 3);
        FakeAdmissionServer admission = FakeAdmissionServer.get();
        FakeReservationServer.get().limparChamadas();
        admission.modoFila(true);

        ReservaEnfileiradaException ex = assertThrows(ReservaEnfileiradaException.class,
                () -> gateway.addLink(new EventWallet(u.getId(), e.getEvent_id()), null));

        assertEquals("queued", ex.estado().status());
        assertEquals(e.getEvent_id(), ex.estado().eventId());
        UUID.fromString(ex.estado().idempotencyKey()); // sem chave do cliente: a gerada, que é a da mensagem
        assertEquals(admission.chamadas().get(0).idempotencyKey(), ex.estado().idempotencyKey());
        assertEquals(0, postsDeReserva(), "enfileirada: o gateway não chama o reservation (o worker do admission o fará)");
    }

    @Test
    void recusaDeNegocioDoAdmissionPropagaTipadaENaoAbreODisjuntor() {
        User u = usuario("gw-adm-fila-2");
        Event e = evento(u.getId(), "GW Admission recusa", 3);
        FakeAdmissionServer admission = FakeAdmissionServer.get();
        FakeReservationServer.get().limparChamadas();
        admission.falhar(429, "Muitas reservas na fila para este usuário");

        for (int i = 0; i < 7; i++) {
            AdmissionRecusadaException ex = assertThrows(AdmissionRecusadaException.class,
                    () -> gateway.addLink(new EventWallet(u.getId(), e.getEvent_id()), "k"));
            assertEquals(429, ex.status());
        }

        assertEquals(7, admission.recebidas(), "429 é resposta de um serviço vivo: o circuito nunca abre por ela");
        assertEquals(0, postsDeReserva(), "recusado: nada de fail-open para o caminho síncrono");
    }

    @Test
    void falhaDoAdmissionNoBracoCasMandaAChaveEfetivaAoReservation() {
        User u = usuario("gw-adm-fila-3");
        User u2 = usuario("gw-adm-fila-4");
        Event e = evento(u.getId(), "GW Admission fail-open chave", 5);
        FakeAdmissionServer admission = FakeAdmissionServer.get();
        FakeReservationServer.get().limparChamadas();
        admission.falhar(503);

        gateway.addLink(new EventWallet(u.getId(), e.getEvent_id()), "chave-do-cliente");
        EventWallet semChave = gateway.addLink(new EventWallet(u2.getId(), e.getEvent_id()), null);

        List<String> chaves = FakeReservationServer.get().chavesDeReserva();
        assertEquals("chave-do-cliente", chaves.get(0), "fail-open com chave do cliente: a mesma");
        UUID.fromString(chaves.get(1)); // sem chave: o reservation recebe a gerada (a mesma que o admission pode ter publicado)
        assertEquals(chaves.get(1), semChave.getIdempotencyKey());
    }

    @Test
    void estadoDaAdmissaoConsultaPeloUsuarioEDevolveVazioQuandoNaoHa() {
        FakeAdmissionServer admission = FakeAdmissionServer.get();
        admission.estado(42L, "k-pending", "{\"status\":\"pending\",\"eventId\":7,\"expiresAt\":\"2026-10-28T12:00:00\"}");

        EstadoDaReserva estado = gateway.estadoDaAdmissao(42L, "k-pending").orElseThrow();

        assertEquals(new EstadoDaReserva("pending", 7L, "k-pending", "2026-10-28T12:00:00", null), estado);
        assertTrue(gateway.estadoDaAdmissao(43L, "k-pending").isEmpty(), "a chave é do usuário 42: para o 43 não existe");
        admission.falhar(503);
        assertThrows(AdmissionUnavailableException.class, () -> gateway.estadoDaAdmissao(42L, "k-pending"));
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
