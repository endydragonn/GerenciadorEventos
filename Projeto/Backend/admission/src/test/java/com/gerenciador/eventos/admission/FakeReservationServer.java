package com.gerenciador.eventos.admission;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * O serviço reservation FALSO dos testes do admission (issue #42): um JDK {@code HttpServer} de verdade (porta sorteada), com o MESMO
 * contrato da API interna que o worker usa ({@code POST /internal/reservations?path=cas} e {@code GET /internal/reservations/replay}) e a
 * lógica mínima do serviço real: CAS em memória numa seção crítica (capacidade por evento), idempotência por chave com escopo por usuário
 * (repetição devolve a reserva salva e não toca a capacidade; chave de outro evento = 422), "Evento lotado" e "Vínculo já existente" (400).
 * Controles para os testes: atraso por chamada, contador de chamadas SIMULTÂNEAS (e o máximo visto), modo "fora" (503) e "comitar e perder a
 * resposta" (grava a reserva e responde 500, a reentrega que o aceite pede). A prova de overselling 0 contra o reservation REAL é do e2e
 * e do dry-run (#43); aqui o CAS é uma seção crítica, o suficiente para provar que o WORKER nunca reserva a mais.
 */
final class FakeReservationServer {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static FakeReservationServer instancia;

    static synchronized FakeReservationServer iniciar() throws IOException {
        if (instancia == null) {
            instancia = new FakeReservationServer();
        }
        return instancia;
    }

    static FakeReservationServer get() {
        return instancia;
    }

    private final HttpServer server;
    private final Map<Long, Integer> capacidade = new ConcurrentHashMap<>();
    private final Map<String, EventWallet> reservas = new ConcurrentHashMap<>(); // "user:event"
    private final Map<String, Long> chaves = new ConcurrentHashMap<>(); // "user:chave" -> eventId
    private final Map<String, LocalDateTime> expiracoes = new ConcurrentHashMap<>();
    private final List<String> chavesRecebidas = new CopyOnWriteArrayList<>(); // Idempotency-Key de cada POST (null = ausente)
    private final List<String> caminhos = new CopyOnWriteArrayList<>(); // "METODO /caminho?query" de cada chamada
    private final AtomicInteger emVoo = new AtomicInteger();
    private final AtomicInteger maxEmVoo = new AtomicInteger();
    private final AtomicInteger falhasRestantes = new AtomicInteger();
    private final AtomicInteger perdasRestantes = new AtomicInteger();
    private volatile boolean fora;
    private volatile long atrasoMs;

    private FakeReservationServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal", this::tratar);
        server.setExecutor(Executors.newFixedThreadPool(64));
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Estado zerado e controles desligados (cada teste começa daqui). */
    synchronized void reset() {
        capacidade.clear();
        reservas.clear();
        chaves.clear();
        expiracoes.clear();
        chavesRecebidas.clear();
        caminhos.clear();
        emVoo.set(0);
        maxEmVoo.set(0);
        falhasRestantes.set(0);
        perdasRestantes.set(0);
        fora = false;
        atrasoMs = 0;
    }

    void capacidade(long eventId, int vagas) {
        capacidade.put(eventId, vagas);
    }

    /** O reservation "fora": toda chamada responde 503. */
    void fora(boolean fora) {
        this.fora = fora;
    }

    /** As próximas {@code n} reservas respondem 503 SEM gravar nada. */
    void falharAsProximas(int n) {
        falhasRestantes.set(n);
    }

    /** As próximas {@code n} reservas COMITAM e respondem 500 (a resposta se perde: a mensagem volta e é reentregue). */
    void perderRespostaDasProximas(int n) {
        perdasRestantes.set(n);
    }

    /** Atraso em cada chamada à reserva (mantém a chamada "em voo", para medir a simultaneidade). */
    void atraso(long ms) {
        this.atrasoMs = ms;
    }

    /** Reserva já comitada (como se o CAS tivesse rodado e a resposta se perdido), sem passar pela API. */
    synchronized void comitar(long userId, long eventId, String chave) {
        gravar(userId, eventId, chave);
    }

    int maxSimultaneas() {
        return maxEmVoo.get();
    }

    /** Quantas vezes o POST de reserva foi chamado (inclui as que falharam). */
    int chamadasDeReserva() {
        return chavesRecebidas.size();
    }

    List<String> chavesRecebidas() {
        return Collections.unmodifiableList(new ArrayList<>(chavesRecebidas));
    }

    List<String> caminhos() {
        return Collections.unmodifiableList(new ArrayList<>(caminhos));
    }

    synchronized int reservasAtivas(long eventId) {
        return (int) reservas.values().stream().filter(r -> r.getEventId() == eventId).count();
    }

    // ===================== roteamento =====================

    private void tratar(HttpExchange ex) throws IOException {
        caminhos.add(ex.getRequestMethod() + " " + ex.getRequestURI());
        try {
            if (fora) {
                responder(ex, 503, "text/plain", "reservation fora");
                return;
            }
            String caminho = ex.getRequestURI().getPath();
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            if ("POST".equals(ex.getRequestMethod()) && "/internal/reservations".equals(caminho)) {
                reservar(ex, q);
            } else if ("GET".equals(ex.getRequestMethod()) && "/internal/reservations/replay".equals(caminho)) {
                replay(ex, q);
            } else {
                responder(ex, 404, "text/plain", "rota desconhecida");
            }
        } catch (Conflito e) {
            responder(ex, 422, "text/plain", e.getMessage());
        } catch (IllegalArgumentException e) {
            responder(ex, 400, "text/plain", e.getMessage());
        } catch (RuntimeException e) {
            responder(ex, 500, "text/plain", String.valueOf(e.getMessage()));
        }
    }

    private void reservar(HttpExchange ex, Map<String, String> q) throws IOException {
        int simultaneas = emVoo.incrementAndGet();
        maxEmVoo.accumulateAndGet(simultaneas, Math::max);
        try {
            JsonNode corpo = JSON.readTree(ex.getRequestBody());
            long userId = corpo.get("userId").asLong();
            long eventId = corpo.get("eventId").asLong();
            String chave = ex.getRequestHeaders().getFirst("Idempotency-Key");
            chavesRecebidas.add(chave);
            if (!"cas".equals(q.get("path"))) {
                throw new IllegalArgumentException("o worker só usa o braço cas");
            }
            if (atrasoMs > 0) {
                dormir(atrasoMs);
            }
            if (falhasRestantes.getAndUpdate(n -> Math.max(n - 1, 0)) > 0) {
                responder(ex, 503, "text/plain", "reservation indisponível (falha injetada)");
                return;
            }
            EventWallet reserva = reservarNoCas(userId, eventId, chave);
            if (perdasRestantes.getAndUpdate(n -> Math.max(n - 1, 0)) > 0) {
                responder(ex, 500, "text/plain", "resposta perdida (falha injetada)");
                return;
            }
            ex.getResponseHeaders().add("Idempotency-Key", chave);
            responder(ex, 201, "application/json", JSON.writeValueAsString(reserva));
        } finally {
            emVoo.decrementAndGet();
        }
    }

    /** O CAS: idempotência por chave (usuário), vínculo único por (usuário, evento) e capacidade, numa seção crítica. */
    private synchronized EventWallet reservarNoCas(long userId, long eventId, String chave) {
        Long salvaDe = chaves.get(userId + ":" + chave);
        if (salvaDe != null) {
            if (salvaDe != eventId) {
                throw new Conflito("Idempotency-Key já usada em outra requisição (evento diferente)");
            }
            return salva(userId, eventId, chave);
        }
        Integer vagas = capacidade.get(eventId);
        if (vagas == null) {
            throw new IllegalArgumentException("Capacidade do evento não configurada");
        }
        if (reservas.containsKey(userId + ":" + eventId)) {
            throw new IllegalArgumentException("Vínculo já existente");
        }
        if (vagas != -1 && reservasAtivas(eventId) >= vagas) {
            throw new IllegalArgumentException("Evento lotado");
        }
        return gravar(userId, eventId, chave);
    }

    private EventWallet gravar(long userId, long eventId, String chave) {
        EventWallet r = new EventWallet(userId, eventId);
        r.setStatus("pending");
        r.setExpiresAt(LocalDateTime.now().plusSeconds(120).truncatedTo(ChronoUnit.MICROS));
        reservas.put(userId + ":" + eventId, r);
        chaves.put(userId + ":" + chave, eventId);
        expiracoes.put(userId + ":" + chave, r.getExpiresAt());
        return r;
    }

    private void replay(HttpExchange ex, Map<String, String> q) throws IOException {
        long userId = Long.parseLong(q.get("userId"));
        long eventId = Long.parseLong(q.get("eventId"));
        String chave = q.get("idempotencyKey");
        EventWallet salva;
        synchronized (this) {
            Long salvaDe = chaves.get(userId + ":" + chave);
            if (salvaDe != null && salvaDe != eventId) {
                throw new Conflito("Idempotency-Key já usada em outra requisição (evento diferente)");
            }
            salva = salvaDe == null ? null : salva(userId, eventId, chave);
        }
        if (salva == null) {
            responder(ex, 404, "text/plain", "sem chave");
        } else {
            responder(ex, 200, "application/json", JSON.writeValueAsString(salva));
        }
    }

    private EventWallet salva(long userId, long eventId, String chave) {
        EventWallet w = new EventWallet(userId, eventId);
        w.setStatus("pending");
        w.setExpiresAt(expiracoes.get(userId + ":" + chave));
        return w;
    }

    // ===================== http =====================

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) {
            return m;
        }
        for (String par : raw.split("&")) {
            String[] kv = par.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }

    private static void responder(HttpExchange ex, int status, String tipo, String corpo) throws IOException {
        byte[] bytes = corpo == null ? new byte[0] : corpo.getBytes(StandardCharsets.UTF_8);
        if (tipo != null) {
            ex.getResponseHeaders().add("Content-Type", tipo);
        }
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }

    private static final class Conflito extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Conflito(String m) {
            super(m);
        }
    }
}
