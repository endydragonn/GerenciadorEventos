package com.gerenciador.eventos.admission;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.admissionclient.api.AdmissionRequest;

/**
 * A mensagem que VENCE NA FILA (issue #42): o {@code x-message-ttl} da {@code admission.requests} a manda à dead-letter exchange, e o
 * listener da {@code admission.timed_out} reconcilia o resultado: {@code timed_out} (nenhuma reserva com a chave, ou o reservation fora/com
 * erro) ou {@code pending} (o replay achou a reserva: o CAS comitou e a resposta se perdeu). Com RabbitMQ e Redis reais, TTL de 400 ms e o
 * worker PARADO (nenhum consumer na fila de requisições: a mensagem envelhece até o TTL). A limpeza das filas é a do fim da sessão
 * ({@code AdmissionTestSession}).
 */
@SpringBootTest(properties = {"internal.api.token=token-de-teste", "admission.amqp.prefix=${admission.test.prefix}dlx-",
        "admission.worker.enabled=true", "admission.queue.ttl-ms=400"})
@DirtiesContext
class AdmissionDeadLetterTest {

    private static final String DLQ = RabbitDeTeste.prefixo("dlx-") + "admission.timed_out";

    @Autowired private FilaDeAdmissao fila;
    @Autowired private AdmissionStore store;
    @Autowired private RabbitAdmin admin;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private RabbitListenerEndpointRegistry registro;
    @Autowired private StringRedisTemplate redis;
    private final FakeReservationServer reservation = FakeReservationServer.get();
    private final long evento = RedisDeTeste.eventoNovo();
    private final long usuario = RedisDeTeste.usuarioNovo();

    @BeforeEach
    void preparar() {
        reservation.reset();
        MessageListenerContainer worker = registro.getListenerContainer("admission-worker");
        assertNotNull(worker, "o worker é o listener admission-worker");
        worker.stop(); // sem consumer: a mensagem só sai da fila pelo TTL
        admin.initialize();
    }

    private void esperar(BooleanSupplier condicao) {
        long fim = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condicao.getAsBoolean()) {
            if (System.nanoTime() > fim) {
                throw new AssertionError("condição não atendida em 15 s; resultado = " + store.resultado(usuario, "chave"));
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String status() {
        ResultadoDaFila r = store.resultado(usuario, "chave");
        return r == null ? null : r.status();
    }

    @Test
    void mensagemVencidaNaFilaVaiAdeadLetterEViraTimedOut() {
        fila.enfileirar(new AdmissionRequest(evento, usuario, "chave", "cas"));
        assertEquals("queued", status());

        esperar(() -> "timed_out".equals(status()));

        assertEquals("timed_out", fila.consultar(usuario, "chave").orElseThrow().status());
        assertEquals(0, redis.opsForZSet().zCard(AdmissionStore.chaveEsperaUsuario(usuario)), "a dead-letter tirou a mensagem da espera");
        assertEquals(0, redis.opsForZSet().zCard(AdmissionStore.chaveEspera(evento)));
        assertEquals(0, reservation.chavesRecebidas().size(), "ninguém chamou a reserva (o worker estava parado)");
        assertTrue(reservation.caminhos().stream().allMatch(c -> c.startsWith("GET /internal/reservations/replay")),
                "só a reconciliação (replay, leitura): " + reservation.caminhos());
        esperar(() -> admin.getQueueInfo(DLQ).getMessageCount() == 0);
    }

    @Test
    void chaveComMaisChavesEECaracteresDeUrlNaoQuebraAReconciliacao() {
        String chave = "abc+d{e}f&g=h"; // base64 tem '+'; '{}' e '&' não podem virar variável de template nem separar parâmetros
        reservation.comitar(usuario, evento, chave);
        fila.enfileirar(new AdmissionRequest(evento, usuario, chave, "cas"));

        esperar(() -> {
            ResultadoDaFila r = store.resultado(usuario, chave);
            return r != null && !"queued".equals(r.status());
        });

        assertEquals("pending", store.resultado(usuario, chave).status(), "o replay achou a reserva da chave exata, sem '+' virar espaço");
    }

    @Test
    void deadLetterDeResultadoJaFinalOuDeOutraTentativaNaoChamaOReplay() throws Exception {
        AdmissionMessage final_ = new AdmissionMessage(usuario, evento, "k-final", "ta", System.currentTimeMillis() + 30_000);
        store.entrar(final_);
        store.concluir(final_, store.resultado(usuario, "k-final").pending("2026-10-28T12:00:00")); // o ack se perdeu, a mensagem volta e vence
        AdmissionMessage nova = new AdmissionMessage(usuario, evento, "k-nova", "tn", System.currentTimeMillis() + 30_000);
        store.entrar(nova);
        AdmissionMessage antiga = new AdmissionMessage(usuario, evento, "k-nova", "t-antiga", System.currentTimeMillis() + 30_000);

        for (AdmissionMessage m : new AdmissionMessage[] {final_, antiga}) {
            rabbit.send(RabbitDeTeste.prefixo("dlx-") + "admission", "event." + evento, MessageBuilder
                    .withBody(new ObjectMapper().writeValueAsBytes(m)).setContentType(MessageProperties.CONTENT_TYPE_JSON).build());
        }

        esperar(() -> admin.getQueueInfo(RabbitDeTeste.prefixo("dlx-") + "admission.requests").getMessageCount() == 0);
        Thread.sleep(1_500); // o TTL de 400 ms já venceu e a dead-letter já foi consumida
        assertEquals(0, reservation.caminhos().size(), "só reconcilia um queued DESTA tentativa: nenhum replay: " + reservation.caminhos());
        assertEquals("pending", store.resultado(usuario, "k-final").status());
        assertEquals("tn", store.resultado(usuario, "k-nova").attemptId());
        assertEquals("queued", store.resultado(usuario, "k-nova").status());
    }

    @Test
    void seOReplayAchaAReservaDaChaveVirapending() {
        reservation.comitar(usuario, evento, "chave"); // o CAS comitou e a resposta se perdeu
        fila.enfileirar(new AdmissionRequest(evento, usuario, "chave", "cas"));

        esperar(() -> !"queued".equals(status()));

        ResultadoDaFila r = store.resultado(usuario, "chave");
        assertEquals("pending", r.status());
        assertNotNull(r.expiresAt(), "o expiresAt da reserva achada");
        assertEquals(0, reservation.chavesRecebidas().size());
    }

    @Test
    void reservationForaNaReconciliacaoViraTimedOutEAckNaoFicaReentregando() {
        reservation.fora(true);
        fila.enfileirar(new AdmissionRequest(evento, usuario, "chave", "cas"));

        esperar(() -> "timed_out".equals(status()));
        esperar(() -> admin.getQueueInfo(DLQ).getMessageCount() == 0);

        int chamadas = reservation.caminhos().size();
        try {
            Thread.sleep(600);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        assertEquals(chamadas, reservation.caminhos().size(), "o listener da dead-letter SEMPRE dá ack: sem laço de reentrega");
        assertEquals(1, chamadas, "uma tentativa de replay, que falhou");
    }
}
