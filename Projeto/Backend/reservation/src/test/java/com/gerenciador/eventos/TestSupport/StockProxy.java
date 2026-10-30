package com.gerenciador.eventos.TestSupport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Proxy HTTP de teste (JDK {@link HttpServer}) entre o {@code StockClient} do reservation e o stock REAL (issue #47). Repassa tudo tal como
 * veio e sabe falhar de três jeitos, que são as falhas da saga:
 * <ul>
 *   <li>{@link #fora()}: o stock "cai" (a porta para de aceitar; {@link #dentro()} a reabre na MESMA porta);</li>
 *   <li>{@link #atrasarHoldEm(long)}: segura o {@code POST /internal/holds} antes de repassá-lo (stock lento);</li>
 *   <li>{@link #atrasarRespostaDoHoldEm(long)}: repassa o hold (o stock COMITA) e segura a resposta (dono lento DEPOIS do hold);</li>
 *   <li>{@link #comitarEPerderRespostaDoHold(boolean)}: repassa o {@code POST /internal/holds} (o stock COMITA o hold) e descarta a resposta,
 *       fechando a conexão sem responder: para o cliente é uma conexão que caiu depois do commit.</li>
 * </ul>
 * O estado é da JVM de teste (compartilhado pelos contextos Spring em cache): cada teste que mexe faz {@link #restaurar()} no fim.
 */
public final class StockProxy {

    private final int destino;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "stock-proxy");
        t.setDaemon(true);
        return t;
    });
    private final List<String> chamadas = new CopyOnWriteArrayList<>();
    private final AtomicInteger holds = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();
    private HttpServer servidor;
    private int porta;
    private volatile long atrasoDoHoldMs;
    private volatile long atrasoDaRespostaDoHoldMs;
    private volatile boolean perderRespostaDoHold;

    StockProxy(int portaDoStock) {
        this.destino = portaDoStock;
        this.servidor = subir(0);
        this.porta = servidor.getAddress().getPort();
    }

    public String url() {
        return "http://localhost:" + porta;
    }

    /** O stock cai: a porta do proxy deixa de aceitar conexão (recusa), até {@link #dentro()}. */
    public synchronized void fora() {
        if (servidor != null) {
            servidor.stop(0);
            servidor = null;
        }
    }

    /** O stock volta, na mesma porta. */
    public synchronized void dentro() {
        if (servidor != null) {
            return;
        }
        for (int i = 0; ; i++) {
            try {
                servidor = subir(porta);
                return;
            } catch (IllegalStateException ex) {
                if (i >= 50) {
                    throw ex;
                }
                dormir(100);
            }
        }
    }

    public void atrasarHoldEm(long ms) {
        this.atrasoDoHoldMs = ms;
    }

    /** O stock COMITA o hold na hora e a resposta ao cliente só sai depois de {@code ms} (dono lento depois do hold, W7). */
    public void atrasarRespostaDoHoldEm(long ms) {
        this.atrasoDaRespostaDoHoldMs = ms;
    }

    public void comitarEPerderRespostaDoHold(boolean ligado) {
        this.perderRespostaDoHold = ligado;
    }

    /** Volta ao normal: dentro, sem atraso, sem perder resposta. */
    public void restaurar() {
        dentro();
        atrasoDoHoldMs = 0;
        atrasoDaRespostaDoHoldMs = 0;
        perderRespostaDoHold = false;
    }

    /** Chamadas repassadas (ou tentadas) desde o último {@link #limparChamadas()}, como {@code "POST /internal/holds"}. */
    public List<String> chamadas() {
        return List.copyOf(chamadas);
    }

    public int holdsRecebidos() {
        return holds.get();
    }

    public int releasesRecebidos() {
        return releases.get();
    }

    public void limparChamadas() {
        chamadas.clear();
        holds.set(0);
        releases.set(0);
    }

    private HttpServer subir(int p) {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(p), 0);
            s.createContext("/", this::tratar);
            s.setExecutor(executor);
            s.start();
            return s;
        } catch (IOException ex) {
            throw new IllegalStateException("proxy do stock não subiu na porta " + p + ": " + ex.getMessage(), ex);
        }
    }

    private void tratar(HttpExchange ex) throws IOException {
        String metodo = ex.getRequestMethod();
        String caminho = ex.getRequestURI().getRawPath();
        boolean hold = "POST".equals(metodo) && "/internal/holds".equals(caminho);
        chamadas.add(metodo + " " + caminho);
        if (hold) {
            holds.incrementAndGet();
            dormir(atrasoDoHoldMs);
        } else if (caminho.endsWith("/release")) {
            releases.incrementAndGet();
        }
        byte[] corpo = ex.getRequestBody().readAllBytes();
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + destino + ex.getRequestURI()))
                .method(metodo, corpo.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(corpo));
        copiar(ex, req, "X-Internal-Token");
        copiar(ex, req, "Content-Type");
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ex.close();
            return;
        }
        if (hold && perderRespostaDoHold) {
            ex.close(); // o stock comitou; o cliente nunca sabe
            return;
        }
        if (hold) {
            dormir(atrasoDaRespostaDoHoldMs);
        }
        resp.headers().firstValue("Content-Type").ifPresent(v -> ex.getResponseHeaders().set("Content-Type", v));
        // sem keep-alive: o pool do cliente nunca guarda conexão que "fora()"/"dentro()" deixaria velha (a queda de verdade é coberta
        // pelo disjuntor de repetir a chave: 503 e a saga reabre); assim cada teste vê a falha que provocou, não a de um socket morto
        ex.getResponseHeaders().set("Connection", "close");
        ex.sendResponseHeaders(resp.statusCode(), resp.body().length == 0 ? -1 : resp.body().length);
        if (resp.body().length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(resp.body());
            }
        }
        ex.close();
    }

    private static void copiar(HttpExchange ex, HttpRequest.Builder req, String header) {
        String v = ex.getRequestHeaders().getFirst(header);
        if (v != null) {
            req.header(header, v);
        }
    }

    private static void dormir(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
