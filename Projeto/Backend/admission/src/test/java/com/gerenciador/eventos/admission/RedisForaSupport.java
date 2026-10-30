package com.gerenciador.eventos.admission;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Chamadas HTTP dos testes de Redis fora no serviço (issue #41). Usa o {@code HttpClient} do JDK, que NÃO repete a requisição: o
 * {@code TestRestTemplate} (HttpClient 5) repete respostas 503 depois de 1 s por padrão, o que somaria ~1 s a todo 503 medido aqui. O
 * 503 do serviço (e não um 200 {@code sync}) é de propósito: o disjuntor do gateway só conta falha.
 */
final class RedisForaSupport {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private RedisForaSupport() { }

    /** Resposta e o tempo que a chamada levou. */
    record Medida(int status, long ms) { }

    static Medida post(int porta, long eventId) {
        String corpo = "{\"eventId\":" + eventId + ",\"userId\":1,\"idempotencyKey\":\"k\",\"path\":\"cas\"}";
        return enviar(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/internal/admissions"))
                .header("Content-Type", "application/json").header("X-Internal-Token", "token-de-teste")
                .POST(HttpRequest.BodyPublishers.ofString(corpo)).build());
    }

    static Medida getRpm(int porta) {
        return enviar(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/internal/admissions/events/5/rpm"))
                .header("X-Internal-Token", "token-de-teste").GET().build());
    }

    static Medida getResultado(int porta) {
        return enviar(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/internal/admissions/result?userId=5&idempotencyKey=k"))
                .header("X-Internal-Token", "token-de-teste").GET().build());
    }

    private static Medida enviar(HttpRequest req) {
        long t0 = System.nanoTime();
        try {
            int status = HTTP.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
            return new Medida(status, (System.nanoTime() - t0) / 1_000_000);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
