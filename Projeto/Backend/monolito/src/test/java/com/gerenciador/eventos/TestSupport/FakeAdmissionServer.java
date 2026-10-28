package com.gerenciador.eventos.TestSupport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Servidor HTTP de verdade (JDK {@code HttpServer}, porta sorteada) que faz o papel do serviço admission nos testes do MONÓLITO
 * (issue #41), como o {@link FakeReservationServer} faz o do reservation: o monólito não tem o código do serviço (nem precisa de
 * Redis), então fala HTTP com este, que devolve {@code sync} (o único modo da #41), valida a chave de idempotência com a regra do
 * serviço real (não vazia, até 255 caracteres), exige o token interno e registra cada chamada. O contador de verdade (Redis) é
 * testado no módulo {@code admission}, e o contrato cliente-servidor no {@code AdmissionApiTest} de lá.
 *
 * <p>Uma instância por JVM de teste, iniciada pelo {@link AdmissionFakeSession} antes de qualquer contexto Spring (a URL vai na
 * propriedade de sistema {@code admission.service.url}). {@link #falhar(int)} faz o serviço responder com esse status (o admission
 * "fora do ar"), para provar o fail-open do gateway; {@link #aoReceber(Consumer)} deixa o teste olhar o estado do mundo no instante em
 * que o admission é chamado (ex.: o reservation ainda não foi chamado).</p>
 */
public final class FakeAdmissionServer {

    /** Uma chamada recebida em {@code POST /internal/admissions}. */
    public record Chamada(Long eventId, Long userId, String idempotencyKey, String path) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static FakeAdmissionServer instancia;

    public static synchronized FakeAdmissionServer iniciar() throws IOException {
        if (instancia == null) {
            instancia = new FakeAdmissionServer();
        }
        return instancia;
    }

    public static FakeAdmissionServer get() {
        return instancia;
    }

    private final HttpServer server;
    private final List<Chamada> chamadas = new CopyOnWriteArrayList<>();
    private final List<String> sem401 = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger recebidas = new java.util.concurrent.atomic.AtomicInteger();
    private volatile int statusForcado;
    private volatile Consumer<Chamada> aoReceber;
    private volatile long rpm = 300;

    private FakeAdmissionServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/admissions", this::tratar);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Esvazia o estado (o {@code GlobalDbTruncator} chama junto com o TRUNCATE). */
    public void reset() {
        chamadas.clear();
        recebidas.set(0);
        sem401.clear();
        statusForcado = 0;
        aoReceber = null;
    }

    public List<Chamada> chamadas() {
        return Collections.unmodifiableList(new ArrayList<>(chamadas));
    }

    /** Toda requisição que chegou ao servidor, inclusive as respondidas com a falha injetada (as chamadas contadas ficam em {@link #chamadas()}). */
    public int recebidas() {
        return recebidas.get();
    }

    /** Chamadas rejeitadas por token ausente/errado ("POST /caminho"). */
    public List<String> recusadasPorToken() {
        return Collections.unmodifiableList(new ArrayList<>(sem401));
    }

    /** {@code 0} = normal; outro valor = responde sempre com esse status (o serviço "fora do ar"). */
    public void falhar(int status) {
        this.statusForcado = status;
    }

    public void aoReceber(Consumer<Chamada> acao) {
        this.aoReceber = acao;
    }

    private void tratar(HttpExchange ex) throws IOException {
        String esperado = System.getenv("INTERNAL_API_TOKEN");
        if (esperado != null && !esperado.equals(ex.getRequestHeaders().getFirst("X-Internal-Token"))) {
            sem401.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            responder(ex, 401, "text/plain", "Token interno ausente ou inválido");
            return;
        }
        recebidas.incrementAndGet();
        int forcado = statusForcado;
        if (forcado != 0) {
            responder(ex, forcado, "text/plain", "admission indisponível (falha injetada)");
            return;
        }
        JsonNode corpo = JSON.readTree(ex.getRequestBody());
        Chamada c = new Chamada(corpo.path("eventId").asLong(), corpo.path("userId").asLong(),
                corpo.path("idempotencyKey").isNull() ? null : corpo.path("idempotencyKey").asText(), corpo.path("path").asText());
        chamadas.add(c);
        Consumer<Chamada> gancho = aoReceber;
        if (gancho != null) {
            gancho.accept(c);
        }
        if (c.idempotencyKey() == null || c.idempotencyKey().isBlank()) {
            responder(ex, 400, "text/plain", "Idempotency-Key é obrigatória");
        } else if (c.idempotencyKey().length() > 255) { // mesma regra e mensagem do serviço real e do reservation
            responder(ex, 400, "text/plain", "Idempotency-Key deve ter no máximo 255 caracteres");
        } else {
            responder(ex, 200, "application/json", "{\"mode\":\"sync\",\"rpm\":" + rpm + "}");
        }
    }

    private static void responder(HttpExchange ex, int status, String tipo, String corpo) throws IOException {
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", tipo);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        ex.close();
    }
}
