package com.gerenciador.eventos.reservationclient;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.sun.net.httpserver.HttpServer;

/**
 * A URL do {@code GET /internal/reservations/replay} (issue #42, achado do revisor): a {@code Idempotency-Key} vai como VARIÁVEL de URI, então
 * {@code +} (comum em base64), {@code &}, {@code =} e {@code {}} chegam codificados ao servidor. Como valor literal de {@code queryParam}, o
 * {@code +} ficava sem codificar (o Tomcat o lê como espaço: o replay não acharia a reserva) e {@code {...}} virava variável de template
 * ({@code IllegalArgumentException}). Servidor HTTP do JDK que devolve 404 e guarda a query CRUA que recebeu.
 */
class ReservationClientReplayTest {

    @Test
    void chaveComCaracteresEspeciaisChegaCodificadaNaQuery() throws Exception {
        AtomicReference<String> queryCrua = new AtomicReference<>();
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/internal/reservations/replay", ex -> {
            queryCrua.set(ex.getRequestURI().getRawQuery());
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        servidor.start();
        try {
            ReservationClient cliente = new ReservationClient("http://127.0.0.1:" + servidor.getAddress().getPort(), "token", 2,
                    Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(20));
            try {
                assertNull(cliente.replay(new EventWallet(5L, 7L), "abc+d{e}f&g=h"), "404 = nenhuma reserva com a chave");
            } finally {
                cliente.close();
            }
        } finally {
            servidor.stop(0);
        }

        assertEquals("userId=5&eventId=7&idempotencyKey=abc%2Bd%7Be%7Df%26g%3Dh", queryCrua.get());
    }
}
