package com.gerenciador.eventos.admissionclient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.gerenciador.eventos.admissionclient.api.AdmissionRequest;
import com.gerenciador.eventos.admissionclient.api.AdmissionResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * O cliente do admission (issue #41) contra um servidor HTTP do JDK (porta sorteada): o pedido que sai, o fail-open em toda falha
 * do serviço (5xx, conexão recusada, timeout, 401), o 400 que NÃO é fail-open, o disjuntor (5 falhas seguidas abrem; uma única
 * sonda depois do tempo; sonda que falha reabre) e o log com limite de taxa.
 */
class AdmissionClientTest {

    private static final AdmissionRequest PEDIDO = new AdmissionRequest(7L, 42L, "chave-1", "cas");

    private HttpServer server;
    private final AtomicInteger acessos = new AtomicInteger();
    private final List<String> corpos = new CopyOnWriteArrayList<>();
    private final List<String> tokens = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String resposta = "{\"mode\":\"sync\",\"rpm\":900}";
    private volatile long atrasoMs;
    private final AtomicLong relogio = new AtomicLong();
    private AdmissionClient client;

    @BeforeEach
    void subir() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/admissions", this::tratar);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        client = novo("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(500));
    }

    @AfterEach
    void descer() throws IOException {
        client.close();
        server.stop(0);
    }

    private AdmissionClient novo(String url, Duration respostaMax) {
        return new AdmissionClient(url, "token-de-teste", 10, Duration.ofMillis(200), Duration.ofMillis(200), respostaMax,
                Duration.ofSeconds(20), 5, Duration.ofSeconds(5), relogio::get);
    }

    private void tratar(HttpExchange ex) throws IOException {
        acessos.incrementAndGet();
        tokens.add(String.valueOf(ex.getRequestHeaders().getFirst("X-Internal-Token")));
        corpos.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " "
                + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (atrasoMs > 0) {
            try {
                Thread.sleep(atrasoMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = resposta.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", status == 200 ? "application/json" : "text/plain");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void enviaOPedidoComOTokenEDevolveOModoDoServico() {
        AdmissionResult r = client.admit(PEDIDO);

        assertEquals(AdmissionResult.sync(900), r);
        assertEquals(1, acessos.get());
        assertEquals(List.of("token-de-teste"), tokens);
        assertEquals(1, corpos.size());
        String corpo = corpos.get(0);
        assertTrue(corpo.startsWith("POST /internal/admissions "), corpo);
        for (String trecho : new String[] {"\"eventId\":7", "\"userId\":42", "\"idempotencyKey\":\"chave-1\"", "\"path\":\"cas\""}) {
            assertTrue(corpo.contains(trecho), trecho + " em " + corpo);
        }
    }

    @Test
    void pedidoRecusado400SegueSincronoSemFalharAReservaENaoContaParaODisjuntor() {
        status = 400;
        resposta = "Idempotency-Key deve ter no máximo 255 caracteres";

        for (int i = 0; i < 8; i++) {
            assertEquals(AdmissionResult.sync(-1), client.admit(PEDIDO), "o admission é só sinal de carga: o 400 dele não falha a reserva");
        }
        assertEquals(8, acessos.get(), "400 é resposta do serviço vivo: o disjuntor nunca abre por ele");
    }

    @Test
    void erro5xxFazFailOpenParaSync() {
        status = 503;
        resposta = "fora";

        assertEquals(AdmissionResult.sync(-1), client.admit(PEDIDO));
    }

    @Test
    void conexaoRecusadaFazFailOpenParaSync() throws IOException {
        int portaFechada;
        try (ServerSocket s = new ServerSocket(0)) {
            portaFechada = s.getLocalPort();
        }
        try (AdmissionClient semServidor = novo("http://127.0.0.1:" + portaFechada, Duration.ofMillis(500))) {
            assertEquals(AdmissionResult.sync(-1), semServidor.admit(PEDIDO));
        }
    }

    @Test
    void timeoutDeRespostaFazFailOpenSemEsperarOServico() throws IOException {
        atrasoMs = 2_000;
        try (AdmissionClient curto = novo("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(300))) {
            long t0 = System.nanoTime();
            AdmissionResult r = curto.admit(PEDIDO);
            long ms = (System.nanoTime() - t0) / 1_000_000;

            assertEquals(AdmissionResult.sync(-1), r);
            assertTrue(ms < 1_500, "o fail-open sai no timeout de resposta (300 ms), não na espera do serviço: " + ms + " ms");
        }
    }

    @Test
    void disjuntorAbreDepoisDeCincoFalhasSeguidasEFechaNaSondaBemSucedida() {
        status = 500;
        for (int i = 0; i < 5; i++) {
            assertEquals(AdmissionResult.sync(-1), client.admit(PEDIDO));
        }
        assertEquals(5, acessos.get());

        status = 200;
        assertEquals(AdmissionResult.sync(-1), client.admit(PEDIDO), "circuito aberto: sync sem chamar o serviço");
        assertEquals(5, acessos.get(), "nenhuma chamada ao admission com o circuito aberto");

        relogio.addAndGet(Duration.ofSeconds(5).toNanos() + 1);
        assertEquals(AdmissionResult.sync(900), client.admit(PEDIDO), "passado o tempo, a próxima chamada testa o serviço");
        assertEquals(6, acessos.get());

        assertEquals(AdmissionResult.sync(900), client.admit(PEDIDO));
        assertEquals(7, acessos.get(), "sonda bem-sucedida fechou o circuito");
    }

    @Test
    void sondaQueFalhaReabreOCircuitoPeloTempoTodo() {
        status = 500;
        for (int i = 0; i < 5; i++) {
            client.admit(PEDIDO);
        }
        relogio.addAndGet(Duration.ofSeconds(5).toNanos() + 1);

        client.admit(PEDIDO); // a sonda: falha
        assertEquals(6, acessos.get());
        client.admit(PEDIDO);
        client.admit(PEDIDO);
        assertEquals(6, acessos.get(), "a sonda falhou: circuito aberto de novo, sem chamadas");
    }

    @Test
    void umSucessoEntreAsFalhasZeraAContagem() {
        for (int rodada = 0; rodada < 3; rodada++) {
            status = 500;
            for (int i = 0; i < 4; i++) {
                client.admit(PEDIDO);
            }
            status = 200;
            client.admit(PEDIDO);
        }
        assertEquals(15, acessos.get(), "4 falhas + 1 sucesso, três vezes: nunca chega a 5 seguidas");
    }

    @Test
    void tokenErradoNoAdmissionELogadoComoErroEFazFailOpen() {
        status = 401;
        resposta = "Token interno ausente ou inválido";
        ListAppender<ILoggingEvent> logs = anexarLogs();

        assertEquals(AdmissionResult.sync(-1), client.admit(PEDIDO));

        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("401")),
                "401 é erro de configuração: log ERROR");
    }

    @Test
    void falhasSeguidasLogamUmaVezPorIntervalo() {
        status = 500;
        ListAppender<ILoggingEvent> logs = anexarLogs();

        for (int i = 0; i < 5; i++) {
            client.admit(PEDIDO);
        }
        assertEquals(1, logs.list.stream().filter(e -> e.getLevel() == Level.WARN).count(),
                "cinco falhas no mesmo instante: uma linha só");

        relogio.addAndGet(Duration.ofSeconds(11).toNanos()); // passa o limite de 10 s (a chamada é a sonda do circuito)
        client.admit(PEDIDO);
        List<ILoggingEvent> avisos = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertEquals(2, avisos.size());
        assertTrue(avisos.get(1).getFormattedMessage().contains("ocorrências desde o último log: 5"),
                avisos.get(1).getFormattedMessage());
    }

    @Test
    void semTokenOClienteNaoSobe() {
        assertThrows(IllegalStateException.class, () -> new AdmissionClient("http://localhost:1", " ", 1,
                Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofSeconds(1), 5,
                Duration.ofSeconds(1), System::nanoTime));
    }

    private static ListAppender<ILoggingEvent> anexarLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(AdmissionClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
