package com.gerenciador.eventos.catalog;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

import com.gerenciador.eventos.catalogclient.CatalogClient;
import com.gerenciador.eventos.catalogclient.api.CatalogUnavailableException;
import com.gerenciador.eventos.catalogclient.api.Event;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * O {@link CatalogClient} diante de um catalog fora do ar (issue #35, spec "Erros e contrato"): porta sem ninguém, resposta 500 e
 * resposta mais lenta que o timeout viram {@link CatalogUnavailableException} — nunca "não encontrado" nem {@code exists == false}
 * — e o cliente não repete a chamada (sem retry). Sem Spring nem banco: servidores HTTP do JDK na porta sorteada.
 */
public class CatalogClientIndisponivelTest {

    private static final Duration TIMEOUT_CURTO = Duration.ofMillis(300);

    private static CatalogClient cliente(String url) {
        return new CatalogClient(url, "token-de-teste", 4, TIMEOUT_CURTO, TIMEOUT_CURTO, TIMEOUT_CURTO, Duration.ofSeconds(20));
    }

    /** Toda chamada que o gateway faz no caminho de leitura/escrita, inclusive {@code exists}, falha como indisponível. */
    private static void todasFalhamComoIndisponivel(CatalogClient c) {
        assertThrows(CatalogUnavailableException.class, () -> c.exists(1L), "exists nunca devolve false por falha");
        assertThrows(CatalogUnavailableException.class, () -> c.findById(1L), "findById não vira 'não encontrado'");
        assertThrows(CatalogUnavailableException.class, () -> c.findCreatorId(1L), "findCreatorId não vira null");
        assertThrows(CatalogUnavailableException.class, () -> c.findImage(1L), "findImage não vira null");
        assertThrows(CatalogUnavailableException.class, () -> c.searchEvents("x"));
        assertThrows(CatalogUnavailableException.class, () -> c.createEvent(new Event()));
    }

    @Test
    void portaSemServicoViraIndisponivel() throws Exception {
        int porta;
        try (ServerSocket s = new ServerSocket(0)) {
            porta = s.getLocalPort(); // fechada ao sair do try: conexão recusada
        }
        CatalogClient c = cliente("http://127.0.0.1:" + porta);
        try {
            todasFalhamComoIndisponivel(c);
        } finally {
            c.close();
        }
    }

    @Test
    void resposta500ViraIndisponivelSemRetry() throws Exception {
        AtomicInteger chamadas = new AtomicInteger();
        HttpServer server = servidor(ex -> responder(ex, 500, chamadas));
        CatalogClient c = cliente("http://127.0.0.1:" + server.getAddress().getPort());
        try {
            todasFalhamComoIndisponivel(c);
            chamadas.set(0);
            assertThrows(CatalogUnavailableException.class, () -> c.exists(1L));
            assertEquals(1, chamadas.get(), "uma falha = uma chamada (sem retry automático)");
        } finally {
            c.close();
            server.stop(0);
        }
    }

    @Test
    void respostaMaisLentaQueOTimeoutViraIndisponivelSemRetry() throws Exception {
        AtomicInteger chamadas = new AtomicInteger();
        HttpServer server = servidor(ex -> {
            try {
                Thread.sleep(TIMEOUT_CURTO.toMillis() * 4);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            responder(ex, 200, chamadas);
        });
        CatalogClient c = cliente("http://127.0.0.1:" + server.getAddress().getPort());
        try {
            assertThrows(CatalogUnavailableException.class, () -> c.exists(1L));
            assertThrows(CatalogUnavailableException.class, () -> c.findById(1L));
            Thread.sleep(TIMEOUT_CURTO.toMillis() * 5); // deixa o servidor terminar as respostas atrasadas
            assertEquals(2, chamadas.get(), "cada chamada chegou uma vez só (sem retry)");
        } finally {
            c.close();
            server.stop(0);
        }
    }

    private static HttpServer servidor(Consumer<HttpExchange> tratar) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", tratar::accept);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        return server;
    }

    private static void responder(HttpExchange ex, int status, AtomicInteger chamadas) {
        chamadas.incrementAndGet();
        try {
            byte[] corpo = (status == 200 ? "true" : "erro").getBytes();
            ex.getResponseHeaders().add("Content-Type", status == 200 ? "application/json" : "text/plain");
            ex.sendResponseHeaders(status, corpo.length);
            ex.getResponseBody().write(corpo);
        } catch (IOException e) {
            // o cliente já desistiu (timeout): a conexão fechou do outro lado
        } finally {
            ex.close();
        }
    }
}
