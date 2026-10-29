package com.gerenciador.eventos.reservation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.TestSupport.StockEmbutido;
import com.gerenciador.eventos.TestSupport.StockProxy;
import com.gerenciador.eventos.reservationclient.ReservationClient;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.stockclient.api.HoldLiberadoException;
import com.gerenciador.eventos.stockclient.api.HoldRequest;

/**
 * Compensação da saga stock + wallet (issue #47) contra o stock REAL ({@link StockEmbutido}) atrás de um proxy de testes que o derruba,
 * atrasa e faz "comitar e perder a resposta" ({@link StockProxy}). Cobre W1, W3, W5, W6, W8, W11 e W12 da spec (W10, o replay, é do
 * {@code ReservationReplayTest}); W2, W4, W7, W9 e W13 dependem do reaper e do failpoint (passada b). Todo teste termina com as invariantes
 * ({@link InvariantesDaSaga}): vaga certa, nenhum wallet sem hold, nenhum hold sem dono, nenhuma saga presa.
 *
 * <p>Sem reaper nesta passada, o teste que PRECISA deixar uma saga {@code compensating} (stock fora) a resolve à mão no fim, do mesmo
 * jeito que o reaper faria ({@code release} + {@code compensating -> compensated}).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
@TestPropertySource(properties = "reservation.expiry.scan.ms=3600000")
public class ReservationSagaTest extends ReservationTestSupport {

    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventWalletRepository eventWalletRepository;
    @Autowired private IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired private ReservationExpiryService reservationExpiryService;
    @Autowired private DatabaseConnection databaseConnection;
    @Autowired private ReservationSaga saga;
    @LocalServerPort private int porta;
    @Value("${internal.api.token}") private String tokenInterno;

    private final StockProxy proxy = StockEmbutido.get().proxy();
    private ReservationClient cliente;

    @BeforeAll
    void abrirCliente() {
        cliente = new ReservationClient("http://localhost:" + porta, tokenInterno, 20, Duration.ofSeconds(2),
                Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30));
    }

    @AfterAll
    void fecharCliente() throws Exception {
        cliente.close();
    }

    @BeforeEach
    @AfterEach
    void proxyNormal() {
        proxy.restaurar();
        proxy.limparChamadas();
        ReflectionTestUtils.setField(saga, "databaseConnection", databaseConnection);
    }

    // ===================== W1: caminho feliz =====================

    @Test
    public void w1_caminhoFelizUmHoldUmSlotReservedComHoldIdESagaReserved() throws Exception {
        Evento e = novoEvento(null, "W1", 3);
        Usuario u = novoUsuario("u");

        EventWallet r = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "w1-chave");

        assertEquals("pending", r.getStatus());
        UUID hold = holdDaSaga("w1-chave", u.getId());
        assertNotNull(hold);
        assertEquals("reserved", statusDaSaga("w1-chave", u.getId()));
        assertEquals(hold, holdDoWalletevent(u.getId(), e.getEvent_id()), "walletevent.hold_id = o hold da saga");
        assertEquals("held", statusDoHold(hold));
        assertEquals(1, contar("SELECT count(*) FROM stock.hold WHERE event_id = ?", e.getEvent_id()), "um hold");
        assertEquals(2, estoque.findByEventId(e.getEvent_id()).getQuant());
        List<String> eventos = consultarTextos("SELECT event_type || '|' || schema_version || '|' || (payload ->> 'holdId') "
                + "FROM reservation.event_store WHERE stream_type = 'reservation.wallet' AND stream_id = ?",
                ReservationEvents.streamId(u.getId(), e.getEvent_id()));
        assertEquals(List.of("SlotReserved|2|" + hold), eventos, "um SlotReserved v2 com o holdId");
        assertEquals(r.getExpiresAt(), expiresAtDaSaga("w1-chave", u.getId()), "a saga guarda o expiresAt devolvido");
        assertEquals(List.of("POST /internal/holds"), proxy.chamadas(), "uma única chamada ao stock");
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }

    // ===================== W3: o stock comitou e a resposta se perdeu =====================

    @Test
    public void w3_respostaPerdidaCompensaInlineELiberaAVagaERepetirReabreComHoldNovo() throws Exception {
        Evento e = novoEvento(null, "W3", 3);
        Usuario u = novoUsuario("u");
        proxy.comitarEPerderRespostaDoHold(true);

        SagaIndisponivelException ex = assertThrows(SagaIndisponivelException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "w3-chave"));

        assertEquals("Reserva não concluída, tente novamente", ex.getMessage());
        UUID perdido = holdDaSaga("w3-chave", u.getId());
        assertEquals("released", statusDoHold(perdido), "o stock comitou o hold e a compensação o liberou");
        assertEquals("compensated", statusDaSaga("w3-chave", u.getId()));
        assertEquals("inline", resolvidaPor("w3-chave", u.getId()));
        assertEquals(3, estoque.findByEventId(e.getEvent_id()).getQuant(), "a vaga voltou");
        assertNull(eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()), "nenhum walletevent");
        // um "reservar" atrasado do MESMO holdId depois da compensação: lápide, 409, sem débito
        assertThrows(HoldLiberadoException.class, () -> estoque.hold(new HoldRequest(perdido, u.getId(), e.getEvent_id(), HoldRequest.CAS)));
        assertEquals(3, estoque.findByEventId(e.getEvent_id()).getQuant());
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());

        // repetir a mesma chave (falha não consome a chave): reabre com hold NOVO e reserva
        proxy.restaurar();
        EventWallet r = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "w3-chave");
        assertEquals("pending", r.getStatus());
        UUID novo = holdDaSaga("w3-chave", u.getId());
        assertNotEquals(perdido, novo);
        assertEquals("reserved", statusDaSaga("w3-chave", u.getId()));
        assertEquals(2, estoque.findByEventId(e.getEvent_id()).getQuant());
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }

    // ===================== W5: a tx2 falha (corrida de "vínculo") =====================

    @Test
    public void w5_duasChavesDoMesmoUsuarioEEventoAoMesmoTempoUmaReservaEAOutraCompensaInline() throws Exception {
        Evento e = novoEvento(null, "W5", 5);
        Usuario u = novoUsuario("u");
        proxy.atrasarHoldEm(300); // as duas passam da pré-checagem antes de qualquer uma comitar

        List<Callable<String>> tarefas = new ArrayList<>();
        for (String chave : List.of("w5-a", "w5-b")) {
            tarefas.add(() -> {
                try {
                    eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), chave);
                    return "ok";
                } catch (IllegalArgumentException ex) {
                    return ex.getMessage();
                }
            });
        }
        List<String> resultados = Concorrencia.emParalelo(tarefas);

        assertEquals(1, resultados.stream().filter("ok"::equals).count(), "uma reserva: " + resultados);
        assertEquals(List.of("Vínculo já existente"), resultados.stream().filter(m -> !"ok".equals(m)).toList());
        assertEquals(1, contar("SELECT count(*) FROM reservation.walletevent WHERE event_id = ?", e.getEvent_id()));
        assertEquals(1, contar("SELECT count(*) FROM reservation.idempotency_key WHERE event_id = ? AND saga_status = 'reserved'", e.getEvent_id()));
        assertEquals(1, contar("SELECT count(*) FROM reservation.idempotency_key WHERE event_id = ? AND saga_status = 'compensated'", e.getEvent_id()));
        assertEquals(1, contar("SELECT count(*) FROM stock.hold WHERE event_id = ? AND status = 'held'", e.getEvent_id()));
        assertEquals(1, contar("SELECT count(*) FROM stock.hold WHERE event_id = ? AND status = 'released'", e.getEvent_id()));
        assertEquals(4, estoque.findByEventId(e.getEvent_id()).getQuant(), "quant = capacidade - 1");
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }

    // ===================== W6: mesma chave concorrente com o stock lento =====================

    @Test
    public void w6_mesmaChaveConcorrenteComStockLentoUmHoldUmaReservaRespostasIguais() throws Exception {
        Evento e = novoEvento(null, "W6", 5);
        Usuario u = novoUsuario("u");
        proxy.atrasarHoldEm(500);

        List<Callable<EventWallet>> tarefas = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            tarefas.add(() -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "w6-chave"));
        }
        List<EventWallet> rs = Concorrencia.emParalelo(tarefas);

        assertEquals("pending", rs.get(0).getStatus());
        assertEquals(rs.get(0).getExpiresAt(), rs.get(1).getExpiresAt(), "as duas respostas são a mesma");
        assertEquals(1, proxy.holdsRecebidos(), "um único hold pedido ao stock");
        assertEquals(1, contar("SELECT count(*) FROM stock.hold WHERE event_id = ?", e.getEvent_id()));
        assertEquals(1, contar("SELECT count(*) FROM reservation.walletevent WHERE event_id = ?", e.getEvent_id()));
        assertEquals("reserved", statusDaSaga("w6-chave", u.getId()));
        assertEquals(4, estoque.findByEventId(e.getEvent_id()).getQuant());
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }

    // ===================== W8: cancelar e expirar com o stock fora =====================

    @Test
    public void w8_cancelarEExpirarComOStockForaRespondemEADevolucaoEsperaOConsumer() throws Exception {
        Evento e = novoEvento(null, "W8", 3);
        Usuario cancela = novoUsuario("c");
        Usuario expira = novoUsuario("x");
        eventWalletService.addLink(new EventWallet(cancela.getId(), e.getEvent_id()), "w8-c");
        eventWalletService.addLink(new EventWallet(expira.getId(), e.getEvent_id()), "w8-x");
        UUID holdCancela = holdDoWalletevent(cancela.getId(), e.getEvent_id());
        UUID holdExpira = holdDoWalletevent(expira.getId(), e.getEvent_id());
        proxy.fora();

        eventWalletService.removeLink(cancela.getId(), e.getEvent_id());
        forcarExpiracao(expira.getId(), e.getEvent_id());
        reservationExpiryService.expireStaleReservations();

        assertEquals("cancelled", eventWalletRepository.currentStatus(cancela.getId(), e.getEvent_id()));
        assertEquals("expired", eventWalletRepository.currentStatus(expira.getId(), e.getEvent_id()));
        assertEquals(List.of("ReservationCancelled|2|" + holdCancela),
                consultarTextos("SELECT event_type || '|' || schema_version || '|' || (payload ->> 'holdId') FROM reservation.event_store "
                        + "WHERE stream_type = 'reservation.wallet' AND stream_id = ? AND event_type = 'ReservationCancelled'",
                        ReservationEvents.streamId(cancela.getId(), e.getEvent_id())));
        assertEquals(List.of("ReservationExpired|2|" + holdExpira),
                consultarTextos("SELECT event_type || '|' || schema_version || '|' || (payload ->> 'holdId') FROM reservation.event_store "
                        + "WHERE stream_type = 'reservation.wallet' AND stream_id = ? AND event_type = 'ReservationExpired'",
                        ReservationEvents.streamId(expira.getId(), e.getEvent_id())));
        assertEquals(1, estoque.findByEventId(e.getEvent_id()).getQuant(), "a vaga espera no tópico: ainda presa");
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id()); // hold held com devolução pendente é permitido

        // o stock volta e o consumer libera; uma 2ª entrega é no-op
        proxy.dentro();
        assertEquals(2, drenarDevolucoes());
        assertEquals(0, drenarDevolucoes(), "reentrega: cada hold é liberado uma vez só");
        assertEquals(3, estoque.findByEventId(e.getEvent_id()).getQuant());
        assertEquals("released", statusDoHold(holdCancela));
        assertEquals("released", statusDoHold(holdExpira));

        // reviver a reserva grava o hold NOVO; reentregar o Cancelled antigo não o libera
        eventWalletService.addLink(new EventWallet(cancela.getId(), e.getEvent_id()), "w8-c2");
        UUID holdNovo = holdDoWalletevent(cancela.getId(), e.getEvent_id());
        assertNotEquals(holdCancela, holdNovo);
        assertEquals(0, drenarDevolucoes());
        assertEquals("held", statusDoHold(holdNovo));
        assertEquals(2, estoque.findByEventId(e.getEvent_id()).getQuant());
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }

    // ===================== W11: reaberturas =====================

    @Test
    public void w11_duasReaberturasParalelasDaMesmaChaveCompensadaUmHoldHeldUmaReserva() throws Exception {
        Evento e = novoEvento(null, "W11", 5);
        Usuario u = novoUsuario("u");
        UUID antigo = deixarCompensada(e, u, "w11-chave");
        proxy.atrasarHoldEm(300);

        List<Callable<EventWallet>> tarefas = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            tarefas.add(() -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "w11-chave"));
        }
        List<EventWallet> rs = Concorrencia.emParalelo(tarefas);

        assertEquals(rs.get(0).getExpiresAt(), rs.get(1).getExpiresAt());
        assertEquals(1, contar("SELECT count(*) FROM stock.hold WHERE event_id = ? AND status = 'held'", e.getEvent_id()), "um só hold held");
        assertEquals(1, contar("SELECT count(*) FROM stock.hold WHERE event_id = ?", e.getEvent_id()) - 1, "mais o antigo, liberado");
        assertEquals("released", statusDoHold(antigo));
        assertEquals(1, contar("SELECT count(*) FROM reservation.walletevent WHERE event_id = ?", e.getEvent_id()));
        assertNotEquals(antigo, holdDaSaga("w11-chave", u.getId()));
        assertEquals("reserved", statusDaSaga("w11-chave", u.getId()));
        assertEquals(1, proxy.holdsRecebidos(), "só uma das reaberturas chamou o stock");
        assertEquals(4, estoque.findByEventId(e.getEvent_id()).getQuant());
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }

    @Test
    public void w11_fimAtrasadoDoHoldAntigoNaoFechaASagaDoNovo() throws Exception {
        Evento e = novoEvento(null, "W11b", 5);
        Usuario u = novoUsuario("u");
        UUID antigo = deixarCompensada(e, u, "w11b-chave");
        proxy.atrasarHoldEm(800);

        List<Callable<Object>> tarefas = new ArrayList<>();
        tarefas.add(() -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "w11b-chave"));
        tarefas.add(() -> {
            // espera a reabertura (reserving com hold NOVO) e então chega o "fim atrasado" da tentativa antiga
            for (int i = 0; i < 100 && !(("reserving".equals(statusDaSaga("w11b-chave", u.getId())))
                    && !antigo.equals(holdDaSaga("w11b-chave", u.getId()))); i++) {
                Thread.sleep(20);
            }
            UUID novo = holdDaSaga("w11b-chave", u.getId());
            assertNotEquals(antigo, novo);
            try (Connection conn = databaseConnection.getConnection()) {
                assertEquals(0, idempotencyKeyRepository.concluir(conn, "w11b-chave", u.getId(), antigo, LocalDateTime.now().plusMinutes(5)));
            }
            assertFalse(idempotencyKeyRepository.paraCompensando("w11b-chave", u.getId(), antigo));
            assertFalse(idempotencyKeyRepository.compensada("w11b-chave", u.getId(), antigo, "reaper"));
            assertFalse(idempotencyKeyRepository.apagarReserving("w11b-chave", u.getId(), antigo));
            assertFalse(idempotencyKeyRepository.reabrir("w11b-chave", u.getId(), antigo, UUID.randomUUID()));
            assertEquals("reserving", statusDaSaga("w11b-chave", u.getId()), "a saga nova segue de pé");
            assertEquals(novo, holdDaSaga("w11b-chave", u.getId()));
            return null;
        });
        Concorrencia.emParalelo(tarefas);

        assertEquals("reserved", statusDaSaga("w11b-chave", u.getId()));
        assertEquals("held", statusDoHold(holdDaSaga("w11b-chave", u.getId())));
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }

    /** A retenção conta a partir da tentativa reaberta: sem renovar o {@code created_at}, a chave reaberta e reservada agora seria apagada em seguida. */
    @Test
    public void w11_reaberturaRenovaOCreatedAtParaARetencao() throws Exception {
        Evento e = novoEvento(null, "W11c", 3);
        Usuario u = novoUsuario("u");
        UUID antigo = deixarCompensada(e, u, "w11c-chave");
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE reservation.idempotency_key SET created_at = now() - interval '48 hours' "
                     + "WHERE idempotency_key = 'w11c-chave'")) {
            assertEquals(1, ps.executeUpdate());
        }

        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "w11c-chave");

        assertEquals(1, contar("SELECT count(*) FROM reservation.idempotency_key WHERE idempotency_key = 'w11c-chave' "
                + "AND created_at > now() - interval '1 hour'"), "created_at renovado pela reabertura");
        idempotencyKeyRepository.deleteOlderThan(24, 1000);
        assertEquals(1, contar("SELECT count(*) FROM reservation.idempotency_key WHERE idempotency_key = 'w11c-chave'"),
                "a retenção não apaga a chave reaberta");
        assertNotEquals(antigo, holdDaSaga("w11c-chave", u.getId()));
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }

    // ===================== W12: espera =====================

    @Test
    public void w12_stockAtrasadoEDonoRecebe400OPedidoConcorrenteRecebeOProprio400EmMenosDeUmSegundo() throws Exception {
        Evento e = novoEvento(null, "W12", 1);
        Usuario ocupa = novoUsuario("o");
        Usuario u = novoUsuario("u");
        eventWalletService.addLink(new EventWallet(ocupa.getId(), e.getEvent_id()), "w12-ocupa");
        proxy.limparChamadas();
        proxy.atrasarHoldEm(200);

        List<Callable<Long>> tarefas = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            tarefas.add(() -> {
                long t0 = System.nanoTime();
                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                        () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "w12-chave"));
                assertTrue(ex.getMessage().toLowerCase().contains("lotado"), ex.getMessage());
                return (System.nanoTime() - t0) / 1_000_000;
            });
        }
        List<Long> ms = Concorrencia.emParalelo(tarefas);

        assertTrue(ms.stream().allMatch(t -> t < 1000), "os dois 400 em menos de 1 s: " + ms);
        assertEquals(0, contar("SELECT count(*) FROM reservation.idempotency_key WHERE idempotency_key = 'w12-chave'"), "rejeição não consome a chave");
        assertEquals(1, contar("SELECT count(*) FROM stock.hold WHERE event_id = ?", e.getEvent_id()), "o 'lotado' não deixa hold");
        assertEquals(0, estoque.findByEventId(e.getEvent_id()).getQuant());
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }

    @Test
    public void w12_sagaCompensatingComOStockForaRepetirAChaveDa503NaHora() throws Exception {
        Evento e = novoEvento(null, "W12b", 3);
        Usuario u = novoUsuario("u");
        proxy.fora();

        SagaIndisponivelException primeira = assertThrows(SagaIndisponivelException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "w12b-chave"));
        assertEquals("Reserva não concluída, tente novamente", primeira.getMessage());
        assertEquals("compensating", statusDaSaga("w12b-chave", u.getId()), "o release também falhou: fica para o reaper");

        long t0 = System.nanoTime();
        SagaIndisponivelException repetida = assertThrows(SagaIndisponivelException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "w12b-chave"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals("Reserva em andamento, tente novamente", repetida.getMessage());
        assertTrue(ms < 1000, "503 sem esperar: " + ms + " ms");
        assertNull(eventWalletService.replay(u.getId(), e.getEvent_id(), "w12b-chave"), "compensating não é resultado salvo");
        InvariantesDaSaga.verificar(databaseConnection, true, e.getEvent_id());

        // faz o que o reaper fará (passada b): libera o hold no stock e fecha a saga
        proxy.dentro();
        UUID hold = holdDaSaga("w12b-chave", u.getId());
        estoque.release(hold, e.getEvent_id(), u.getId());
        assertTrue(idempotencyKeyRepository.compensada("w12b-chave", u.getId(), hold, "reaper"));
        assertEquals(3, estoque.findByEventId(e.getEvent_id()).getQuant());
        EventWallet r = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "w12b-chave");
        assertEquals("pending", r.getStatus(), "a mesma chave reserva depois");
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }

    // ===================== W14: desfecho incerto da tx2 (revisão da a2-1) =====================

    @Test
    public void w14_commitDaTx2ComitaEDepoisLancaNaoCompensaResponde503ESagaFicaReserved() throws Exception {
        desfechoIncerto("commit");
    }

    @Test
    public void w14_setAutoCommitDepoisDoCommitLancaNaoCompensa() throws Exception {
        desfechoIncerto("setAutoCommit");
    }

    @Test
    public void w14_closeDepoisDoCommitLancaNaoCompensa() throws Exception {
        desfechoIncerto("close");
    }

    /** O commit nem chegou ao banco: a linha segue reserving e NÃO se compensa inline (o reaper decide); o hold segue held. */
    @Test
    public void w14_commitLancaSemComitarDeixaSagaReservingParaOReaperSemRelease() throws Exception {
        Evento e = novoEvento(null, "W14c", 3);
        Usuario u = novoUsuario("u");
        ReflectionTestUtils.setField(saga, "databaseConnection", new FalhaNaTx2(databaseConnection, "commit", false));

        SagaIndisponivelException ex = assertThrows(SagaIndisponivelException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "w14c-chave"));

        assertEquals("Reserva não concluída, tente novamente", ex.getMessage());
        UUID hold = holdDaSaga("w14c-chave", u.getId());
        assertEquals("reserving", statusDaSaga("w14c-chave", u.getId()));
        assertEquals("held", statusDoHold(hold));
        assertNull(eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()), "nada comitou");
        assertEquals(List.of("POST /internal/holds"), proxy.chamadas(), "nenhum release");
        InvariantesDaSaga.verificar(databaseConnection, true, e.getEvent_id());

        // o que o reaper fará (passada b): compensa a órfã
        assertTrue(idempotencyKeyRepository.paraCompensando("w14c-chave", u.getId(), hold));
        estoque.release(hold, e.getEvent_id(), u.getId());
        assertTrue(idempotencyKeyRepository.compensada("w14c-chave", u.getId(), hold, "reaper"));
        assertEquals(3, estoque.findByEventId(e.getEvent_id()).getQuant());
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }

    /** Compensação que acha 0 linhas e a linha já é {@code reserved} com ESTE hold: não libera (seria overselling). */
    @Test
    public void w14_compensacaoComZeroLinhasELinhaReservedComOMeuHoldNaoLibera() throws Exception {
        Evento e = novoEvento(null, "W14d", 3);
        Usuario u = novoUsuario("u");
        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "w14d-chave");
        UUID hold = holdDaSaga("w14d-chave", u.getId());
        proxy.limparChamadas();

        saga.compensarInline(new EventWallet(u.getId(), e.getEvent_id()), "w14d-chave", hold);

        assertEquals(List.of(), proxy.chamadas(), "nenhum release");
        assertEquals("held", statusDoHold(hold));
        assertEquals("reserved", statusDaSaga("w14d-chave", u.getId()));
        assertEquals(2, estoque.findByEventId(e.getEvent_id()).getQuant());
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }

    /** O commit da tx2 chega ao banco e a falha vem depois ({@code metodo}): 503, e a reserva vale (saga reserved, hold held). */
    private void desfechoIncerto(String metodo) throws Exception {
        Evento e = novoEvento(null, "W14-" + metodo, 3);
        Usuario u = novoUsuario("u");
        String chave = "w14-" + metodo;
        ReflectionTestUtils.setField(saga, "databaseConnection", new FalhaNaTx2(databaseConnection, metodo, true));

        SagaIndisponivelException ex = assertThrows(SagaIndisponivelException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), chave));

        assertEquals("Reserva não concluída, tente novamente", ex.getMessage());
        UUID hold = holdDaSaga(chave, u.getId());
        assertEquals("reserved", statusDaSaga(chave, u.getId()), "comitou: ninguém compensa");
        assertEquals("held", statusDoHold(hold), "o hold NÃO foi liberado");
        assertEquals("pending", eventWalletRepository.currentStatus(u.getId(), e.getEvent_id()));
        assertEquals(hold, holdDoWalletevent(u.getId(), e.getEvent_id()));
        assertEquals(2, estoque.findByEventId(e.getEvent_id()).getQuant(), "sem overselling: a vaga segue debitada");
        assertEquals(List.of("POST /internal/holds"), proxy.chamadas(), "nenhum release");
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());

        // repetir a chave devolve o resultado salvo (a conexão volta ao normal)
        ReflectionTestUtils.setField(saga, "databaseConnection", databaseConnection);
        EventWallet repetida = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), chave);
        assertEquals("pending", repetida.getStatus());
        assertEquals(expiresAtDaSaga(chave, u.getId()), repetida.getExpiresAt());
        assertEquals(2, estoque.findByEventId(e.getEvent_id()).getQuant());
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }

    /**
     * {@link DatabaseConnection} de teste cujas conexões lançam {@code SQLException} no {@code metodo} (uma vez só); com {@code comitarAntes}
     * o {@code commit} chega ao banco antes (ack perdido, conexão morta no fim). {@code setAutoCommit} só falha com {@code true} (o do
     * {@code finally}). Só a tx2 da saga usa esta instância.
     */
    private static final class FalhaNaTx2 extends DatabaseConnection {
        private final DatabaseConnection real;
        private final String metodo;
        private final boolean comitarAntes;
        private boolean disparou;

        FalhaNaTx2(DatabaseConnection real, String metodo, boolean comitarAntes) {
            super(null);
            this.real = real;
            this.metodo = metodo;
            this.comitarAntes = comitarAntes;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection c = real.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
                    (p, m, args) -> {
                        boolean alvo = !disparou && m.getName().equals(metodo)
                                && (!m.getName().equals("setAutoCommit") || Boolean.TRUE.equals(args[0]));
                        try {
                            if (alvo && !comitarAntes) {
                                disparou = true;
                                throw new SQLException("falha injetada em " + metodo + " (sem comitar)");
                            }
                            Object r = m.invoke(c, args);
                            if (alvo) {
                                disparou = true;
                                throw new SQLException("falha injetada depois de " + metodo);
                            }
                            return r;
                        } catch (InvocationTargetException ex) {
                            throw ex.getCause();
                        }
                    });
        }
    }

    // ===================== a API interna: 503 e disponibilidade =====================

    /** O 503 da API interna chega ao {@code ReservationClient} como RuntimeException (nunca como 400 de negócio). */
    @Test
    public void stockForaPeloHttpDa503QueViraRuntimeExceptionNoCliente() throws Exception {
        Evento e = novoEvento(null, "HTTP503", 3);
        Usuario u = novoUsuario("u");
        proxy.fora();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> cliente.addLink(new EventWallet(u.getId(), e.getEvent_id()), "http503-chave", false));
        assertFalse(ex instanceof IllegalArgumentException, ex.toString());
        assertTrue(ex.getMessage().contains("503"), ex.getMessage());
        // a disponibilidade com o stock fora também é 503, NUNCA null/404 ("Capacidade do evento não configurada")
        RuntimeException disp = assertThrows(RuntimeException.class, () -> cliente.disponibilidade(e.getEvent_id()));
        assertTrue(disp.getMessage().contains("503"), disp.getMessage());
        assertThrows(SagaIndisponivelException.class, () -> eventWalletService.disponibilidade(e.getEvent_id()));

        proxy.dentro();
        UUID hold = holdDaSaga("http503-chave", u.getId());
        estoque.release(hold, e.getEvent_id(), u.getId());
        idempotencyKeyRepository.compensada("http503-chave", u.getId(), hold, "reaper");
        assertEquals(3, eventWalletService.disponibilidade(e.getEvent_id()));
        InvariantesDaSaga.verificar(databaseConnection, e.getEvent_id());
    }

    // ===================== helpers =====================

    /** Reserva com o proxy "comitando e perdendo a resposta": o hold fica released e a saga compensated. @return o hold perdido */
    private UUID deixarCompensada(Evento e, Usuario u, String chave) throws Exception {
        proxy.comitarEPerderRespostaDoHold(true);
        assertThrows(SagaIndisponivelException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), chave));
        proxy.restaurar();
        proxy.limparChamadas();
        assertEquals("compensated", statusDaSaga(chave, u.getId()));
        return holdDaSaga(chave, u.getId());
    }

    private void forcarExpiracao(Long userId, Long eventId) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE reservation.walletevent SET expires_at = now() - interval '1 minute' "
                     + "WHERE user_id = ? AND event_id = ? AND status = 'pending'")) {
            ps.setLong(1, userId);
            ps.setLong(2, eventId);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private String statusDaSaga(String chave, Long userId) throws Exception {
        return (String) escalar("SELECT saga_status FROM reservation.idempotency_key WHERE idempotency_key = ? AND user_id = ?", chave, userId);
    }

    private String resolvidaPor(String chave, Long userId) throws Exception {
        return (String) escalar("SELECT resolved_by FROM reservation.idempotency_key WHERE idempotency_key = ? AND user_id = ?", chave, userId);
    }

    private UUID holdDaSaga(String chave, Long userId) throws Exception {
        return (UUID) escalar("SELECT hold_id FROM reservation.idempotency_key WHERE idempotency_key = ? AND user_id = ?", chave, userId);
    }

    private LocalDateTime expiresAtDaSaga(String chave, Long userId) throws Exception {
        java.sql.Timestamp t = (java.sql.Timestamp) escalar("SELECT expires_at FROM reservation.idempotency_key WHERE idempotency_key = ? AND user_id = ?",
                chave, userId);
        return t == null ? null : t.toLocalDateTime();
    }

    private UUID holdDoWalletevent(Long userId, Long eventId) throws Exception {
        return (UUID) escalar("SELECT hold_id FROM reservation.walletevent WHERE user_id = ? AND event_id = ?", userId, eventId);
    }

    private String statusDoHold(UUID hold) throws Exception {
        return (String) escalar("SELECT status FROM stock.hold WHERE hold_id = ?", hold);
    }

    private int contar(String sql, Object... params) throws Exception {
        return ((Number) escalar(sql, params)).intValue();
    }

    private Object escalar(String sql, Object... params) throws Exception {
        try (Connection conn = databaseConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        }
    }

    private List<String> consultarTextos(String sql, Object... params) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection conn = databaseConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }
}
