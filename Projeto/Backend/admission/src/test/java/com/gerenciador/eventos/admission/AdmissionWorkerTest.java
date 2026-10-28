package com.gerenciador.eventos.admission;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.admissionclient.api.AdmissionRequest;

/**
 * O worker pool da fila de admissão (issue #42) com RabbitMQ e Redis REAIS e um reservation FALSO (JDK HttpServer, CAS em memória), os
 * 4 casos do aceite e o resto do desenho: reentrega da mesma mensagem não reserva duas vezes, a concorrência fica no configurado (4), com
 * mais mensagens que vagas o overselling é 0, 4xx de negócio = resultado final e ack, reservation fora/5xx/exceção = backoff e a mensagem
 * volta (nenhum worker preso), tentativa antiga não mexe na nova, mensagem ilegível é descartada, mensagem vencida é reconciliada.
 * (A mensagem VENCIDA NA FILA indo à dead-letter é do {@code AdmissionDeadLetterTest}.)
 */
@SpringBootTest(properties = {"internal.api.token=token-de-teste", "admission.amqp.prefix=${admission.test.prefix}worker-",
        "admission.worker.enabled=true", "admission.worker.concurrency=4", "admission.retry-backoff-ms=100"})
@DirtiesContext
class AdmissionWorkerTest {

    private static final String FILA = RabbitDeTeste.prefixo("worker-") + "admission.requests";
    private static final String EXCHANGE = RabbitDeTeste.prefixo("worker-") + "admission";

    @Autowired private FilaDeAdmissao fila;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private RabbitAdmin admin;
    @Autowired private StringRedisTemplate redis;
    @MockitoSpyBean private AdmissionStore store;
    private final ObjectMapper json = new ObjectMapper();
    private final FakeReservationServer reservation = FakeReservationServer.get();
    private final long evento = RedisDeTeste.eventoNovo();
    private long proximoUsuario = RedisDeTeste.usuarioNovo();

    @BeforeEach
    void preparar() {
        reservation.reset();
        reservation.capacidade(evento, 100);
    }

    private long novoUsuario() {
        return proximoUsuario++;
    }

    private void enfileirar(long usuario, long ev, String chave) {
        assertTrue(fila.enfileirar(new AdmissionRequest(ev, usuario, chave, "cas")).publicada());
    }

    private ResultadoDaFila aguardarFinal(long usuario, String chave) {
        aguardar(() -> {
            ResultadoDaFila r = store.resultado(usuario, chave);
            return r != null && !"queued".equals(r.status());
        }, Duration.ofSeconds(20));
        return store.resultado(usuario, chave);
    }

    private static void aguardar(BooleanSupplier condicao, Duration limite) {
        long fim = System.nanoTime() + limite.toNanos();
        while (!condicao.getAsBoolean()) {
            if (System.nanoTime() > fim) {
                throw new AssertionError("condição não atendida em " + limite);
            }
            dormir(25);
        }
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private int mensagensNaFila() {
        return admin.getQueueInfo(FILA).getMessageCount();
    }

    private void publicarCru(byte[] corpo) {
        Message m = MessageBuilder.withBody(corpo).setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
        rabbit.send(EXCHANGE, "event." + evento, m);
    }

    private void publicar(AdmissionMessage m) throws Exception {
        publicarCru(json.writeValueAsBytes(m));
    }

    @Test
    void reservaFelizViraPendingComExpiresAtEUsaAChaveDaMensagem() {
        long usuario = novoUsuario();
        enfileirar(usuario, evento, "chave-feliz");

        ResultadoDaFila r = aguardarFinal(usuario, "chave-feliz");

        assertEquals("pending", r.status());
        assertNotNull(r.expiresAt(), "o expiresAt do walletevent");
        assertEquals(List.of("chave-feliz"), reservation.chavesRecebidas(), "a Idempotency-Key da mensagem");
        assertEquals(List.of("POST /internal/reservations?path=cas"), reservation.caminhos(), "uma chamada, braço cas");
        assertEquals(1, reservation.reservasAtivas(evento));
        assertEquals(0, redis.opsForZSet().zCard(AdmissionStore.chaveEspera(evento)), "o worker tirou a mensagem da espera");
        assertEquals(0, redis.opsForZSet().zCard(AdmissionStore.chaveEsperaUsuario(usuario)));
        assertEquals("pending", fila.consultar(usuario, "chave-feliz").orElseThrow().status());
    }

    @Test
    void recusaDeNegocioViraRejectedComOMotivoEAcknowledge() throws Exception {
        reservation.capacidade(evento, 1);
        long a = novoUsuario();
        long b = novoUsuario();
        enfileirar(a, evento, "a1");
        assertEquals("pending", aguardarFinal(a, "a1").status());

        enfileirar(b, evento, "b1");
        enfileirar(a, evento, "a2");
        long semCapacidade = RedisDeTeste.eventoNovo();
        long c = novoUsuario();
        enfileirar(c, semCapacidade, "c1");
        long d = novoUsuario();
        long outroEvento = RedisDeTeste.eventoNovo();
        reservation.capacidade(outroEvento, 5);
        reservation.comitar(d, outroEvento, "d1"); // a chave foi usada só no caminho síncrono: o admission não a conhece
        enfileirar(d, evento, "d1");

        ResultadoDaFila lotado = aguardarFinal(b, "b1");
        assertEquals("rejected", lotado.status());
        assertEquals("Evento lotado", lotado.reason());
        assertEquals("Vínculo já existente", aguardarFinal(a, "a2").reason());
        assertEquals("Capacidade do evento não configurada", aguardarFinal(c, "c1").reason());
        ResultadoDaFila conflito = aguardarFinal(d, "d1");
        assertEquals("rejected", conflito.status(), "422 do reservation (chave de outro evento) também é resultado final");
        assertEquals("Idempotency-Key já usada em outra requisição (evento diferente)", conflito.reason());

        int chamadas = reservation.chamadasDeReserva();
        dormir(600);
        assertEquals(chamadas, reservation.chamadasDeReserva(), "4xx de negócio é ack: ninguém reentrega");
        assertEquals(0, mensagensNaFila());
        assertEquals(1, reservation.reservasAtivas(evento));
    }

    @Test
    void maisMensagensQueCapacidadeNuncaOversela() {
        reservation.capacidade(evento, 20);
        List<Long> usuarios = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            long u = novoUsuario();
            usuarios.add(u);
            enfileirar(u, evento, "k-" + u);
        }

        long pendentes = 0;
        long rejeitadas = 0;
        for (long u : usuarios) {
            ResultadoDaFila r = aguardarFinal(u, "k-" + u);
            if ("pending".equals(r.status())) {
                pendentes++;
            } else {
                assertEquals("rejected", r.status());
                assertEquals("Evento lotado", r.reason());
                rejeitadas++;
            }
        }

        assertEquals(20, pendentes, "exatamente a capacidade");
        assertEquals(40, rejeitadas);
        assertEquals(20, reservation.reservasAtivas(evento), "overselling 0");
        assertEquals(60, reservation.chamadasDeReserva(), "uma chamada por mensagem (sem 5xx, sem reentrega)");
    }

    @Test
    void aConcorrenciaDoWorkerFicaLimitadaAoConfigurado() {
        reservation.capacidade(evento, -1);
        reservation.atraso(150);
        List<Long> usuarios = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long u = novoUsuario();
            usuarios.add(u);
            enfileirar(u, evento, "k-" + u);
        }

        for (long u : usuarios) {
            assertEquals("pending", aguardarFinal(u, "k-" + u).status());
        }

        int maximo = reservation.maxSimultaneas();
        assertTrue(maximo <= 4, "teto de CAS simultâneos = ADMISSION_WORKER_CONCURRENCY (4): " + maximo);
        assertTrue(maximo >= 3, "e o pool de fato trabalha em paralelo: " + maximo);
    }

    @Test
    void reentregaDeUmCasQueComitouNaoReservaDuasVezes() {
        reservation.capacidade(evento, 5);
        reservation.perderRespostaDasProximas(1); // o CAS comita e a resposta se perde: o worker devolve a mensagem
        long usuario = novoUsuario();
        enfileirar(usuario, evento, "chave-perdida");

        ResultadoDaFila r = aguardarFinal(usuario, "chave-perdida");

        assertEquals("pending", r.status());
        assertEquals(2, reservation.chamadasDeReserva(), "a 2ª é a reentrega");
        assertEquals(List.of("chave-perdida", "chave-perdida"), reservation.chavesRecebidas(), "a mesma Idempotency-Key");
        assertEquals(1, reservation.reservasAtivas(evento), "o reservation deduplica pela chave: uma vaga só");
        verify(store, atLeastOnce()).voltarParaEspera(any());
        assertEquals(0, redis.opsForZSet().zCard(AdmissionStore.chaveEspera(evento)), "depois de pega de novo, sai da espera");
    }

    @Test
    void amesmaMensagemReentregueDepoisDoResultadoFinalSoDaAckSemChamarOReservation() throws Exception {
        long usuario = novoUsuario();
        AdmissionMessage m = new AdmissionMessage(usuario, evento, "chave-dupla", "tentativa-1", System.currentTimeMillis() + 30_000);
        store.entrar(m);
        publicar(m);
        assertEquals("pending", aguardarFinal(usuario, "chave-dupla").status());

        publicar(m); // a mesma mensagem de novo (reentrega de um ack perdido, por exemplo)

        aguardar(() -> mensagensNaFila() == 0, Duration.ofSeconds(10));
        dormir(500);
        assertEquals(1, reservation.chamadasDeReserva(), "o resultado já é final: só ack, sem chamar o reservation");
        assertEquals(1, reservation.reservasAtivas(evento));
    }

    @Test
    void duasCopiasDaMesmaMensagemSimultaneasReservamUmaVezSo() throws Exception {
        long usuario = novoUsuario();
        AdmissionMessage m = new AdmissionMessage(usuario, evento, "chave-dupla", "tentativa-1", System.currentTimeMillis() + 30_000);
        store.entrar(m);

        publicar(m);
        publicar(m);

        assertEquals("pending", aguardarFinal(usuario, "chave-dupla").status());
        aguardar(() -> mensagensNaFila() == 0, Duration.ofSeconds(10));
        dormir(500);
        assertEquals(1, reservation.reservasAtivas(evento), "as duas chamadas levam a MESMA chave: o reservation deduplica");
        assertEquals(List.of(), reservation.chavesRecebidas().stream().filter(k -> !"chave-dupla".equals(k)).toList());
    }

    @Test
    void excecaoNoWorkerDevolveAMensagemENaoPrendeNenhumWorker() {
        doThrow(new QueryTimeoutException("redis fora (injetado)")).doCallRealMethod().when(store).sairDaEspera(any());
        long usuario = novoUsuario();
        enfileirar(usuario, evento, "chave-excecao");

        assertEquals("pending", aguardarFinal(usuario, "chave-excecao").status(), "nack com requeue e reprocessada");

        List<Long> outros = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            long u = novoUsuario();
            outros.add(u);
            enfileirar(u, evento, "k-" + u);
        }
        for (long u : outros) {
            assertEquals("pending", aguardarFinal(u, "k-" + u).status(), "nenhum worker ficou preso");
        }
        verify(store, atLeast(2)).sairDaEspera(any());
        assertEquals(0, mensagensNaFila());
    }

    @Test
    void erroDaJvmNoWorkerTambemDevolveAMensagem() {
        doThrow(new LinkageError("injetado")).doCallRealMethod().when(store).sairDaEspera(any());
        long usuario = novoUsuario();
        enfileirar(usuario, evento, "chave-error");

        assertEquals("pending", aguardarFinal(usuario, "chave-error").status(), "um Error não deixa a mensagem sem ack nem o worker preso");
        verify(store, atLeastOnce()).voltarParaEspera(any());
    }

    @Test
    void reservationForaOuComErro5xxDevolveAMensagemComBackoffEProcessaQuandoVolta() {
        reservation.fora(true);
        List<Long> usuarios = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long u = novoUsuario();
            usuarios.add(u);
            enfileirar(u, evento, "k-" + u);
        }

        dormir(900);
        for (long u : usuarios) {
            assertEquals("queued", store.resultado(u, "k-" + u).status(), "com o reservation fora a mensagem não vira resultado final");
        }
        assertTrue(reservation.caminhos().size() >= 6, "voltou várias vezes, com backoff: " + reservation.caminhos().size());
        verify(store, atLeast(3)).voltarParaEspera(any());

        reservation.fora(false);
        reservation.falharAsProximas(2); // e ainda 2 respostas 5xx
        for (long u : usuarios) {
            assertEquals("pending", aguardarFinal(u, "k-" + u).status());
        }
        assertEquals(3, reservation.reservasAtivas(evento));
    }

    @Test
    void tentativaAntigaNaoMexeNaNovaDaMesmaChave() throws Exception {
        long usuario = novoUsuario();
        AdmissionMessage nova = new AdmissionMessage(usuario, evento, "chave-reusada", "tentativa-nova", System.currentTimeMillis() + 30_000);
        store.entrar(nova); // o resultado é queued da tentativa NOVA; a mensagem dela não foi publicada
        AdmissionMessage antiga = new AdmissionMessage(usuario, evento, "chave-reusada", "tentativa-antiga", System.currentTimeMillis() + 30_000);

        publicar(antiga); // reentrega de uma tentativa antiga

        aguardar(() -> mensagensNaFila() == 0, Duration.ofSeconds(10));
        dormir(500);
        assertEquals(0, reservation.chamadasDeReserva(), "tentativa antiga: ack sem chamar o reservation");
        ResultadoDaFila r = store.resultado(usuario, "chave-reusada");
        assertEquals("queued", r.status());
        assertEquals("tentativa-nova", r.attemptId());
        assertEquals(1, redis.opsForZSet().zCard(AdmissionStore.chaveEsperaUsuario(usuario)), "a espera da tentativa nova segue intacta");
    }

    @Test
    void mensagemIlegivelEDescartadaComAckESeguintesSaoProcessadas() {
        publicarCru("isto não é json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        publicarCru("{\"userId\":1,\"eventId\":2,\"idempotencyKey\":\"sem-tentativa\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long usuario = novoUsuario();
        enfileirar(usuario, evento, "chave-depois-do-lixo");

        assertEquals("pending", aguardarFinal(usuario, "chave-depois-do-lixo").status());
        dormir(500);
        assertEquals(0, mensagensNaFila(), "o lixo (inclusive a mensagem com campo nulo) não ficou voltando para a fila");
        assertEquals(1, reservation.chamadasDeReserva());
    }

    @Test
    void mensagemComDeadlineVencidoEReconciliadaEmVezDeReservar() throws Exception {
        long semReserva = novoUsuario();
        long comReserva = novoUsuario();
        AdmissionMessage a = new AdmissionMessage(semReserva, evento, "k-sem", "ta", System.currentTimeMillis() - 1_000);
        AdmissionMessage b = new AdmissionMessage(comReserva, evento, "k-com", "tb", System.currentTimeMillis() - 1_000);
        store.entrar(a);
        store.entrar(b);
        reservation.comitar(comReserva, evento, "k-com"); // o CAS comitou e a resposta se perdeu

        publicar(a);
        publicar(b);

        assertEquals("timed_out", aguardarFinal(semReserva, "k-sem").status());
        ResultadoDaFila achada = aguardarFinal(comReserva, "k-com");
        assertEquals("pending", achada.status(), "o replay achou a reserva da chave");
        assertNotNull(achada.expiresAt());
        assertEquals(0, reservation.chavesRecebidas().size(), "nenhum POST de reserva: só a reconciliação (GET replay)");
        assertTrue(reservation.caminhos().stream().allMatch(c -> c.startsWith("GET /internal/reservations/replay")));
    }
}
