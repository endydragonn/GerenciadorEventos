package com.gerenciador.eventos.TestSupport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gerenciador.eventos.stockclient.api.EventCap;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Servidor HTTP de verdade (JDK {@code HttpServer}, porta sorteada) no papel do serviço stock (capacidade, #47) nos testes do módulo
 * {@code catalog} (issue #35): só a parte de capacidade que o catalog chama ({@code /internal/eventcaps}: PUT idempotente, GET,
 * POST {@code batch}, PATCH {@code policy} com 404 sem eventcap, DELETE com 409 se o evento "tem reserva"). Versão enxuta do
 * {@code FakeReservationServer} do monólito; o serviço real e o cliente real são testados no módulo {@code reservation}.
 *
 * <p>Uma instância por JVM de teste, iniciada pelo {@link EventCapFakeSession} antes de qualquer contexto Spring (URL na
 * propriedade de sistema {@code stock.service.url}).</p>
 */
public final class FakeEventCapServer {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static FakeEventCapServer instancia;

    public static synchronized FakeEventCapServer iniciar() throws IOException {
        if (instancia == null) {
            instancia = new FakeEventCapServer();
        }
        return instancia;
    }

    public static FakeEventCapServer get() {
        return instancia;
    }

    private final HttpServer server;
    private final Map<Long, EventCap> caps = new ConcurrentHashMap<>();
    private final Set<Long> comReserva = ConcurrentHashMap.newKeySet();
    private final List<String> chamadas = new CopyOnWriteArrayList<>();
    private volatile boolean falharCriacao;
    private volatile int statusPolitica;

    private FakeEventCapServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/eventcaps", this::tratar);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Esvazia o estado (o {@code GlobalDbTruncator} chama junto com o TRUNCATE: os ids de evento voltam a 1). */
    public void reset() {
        caps.clear();
        comReserva.clear();
        chamadas.clear();
        falharCriacao = false;
        statusPolitica = 0;
    }

    /** Faz o {@code PUT /internal/eventcaps/{id}} responder 500 (o reservation "fora do ar" na criação do evento). */
    public void falharCriacao(boolean falhar) {
        this.falharCriacao = falhar;
    }

    /** Faz o {@code PATCH /internal/eventcaps/{id}/policy} responder esse status (ex.: 400 capacidade abaixo do reservado); 0 desliga. */
    public void recusarPolitica(int status) {
        this.statusPolitica = status;
    }

    /** "METODO /caminho" de cada chamada recebida, na ordem. */
    public List<String> chamadas() {
        return List.copyOf(chamadas);
    }

    public void limparChamadas() {
        chamadas.clear();
    }

    /** O evento passa a "ter reserva": apagar a capacidade dele responde 409, como o serviço real. */
    public void marcarReserva(long eventId) {
        comReserva.add(eventId);
    }

    public EventCap capacidade(long eventId) {
        return caps.get(eventId);
    }

    private void tratar(HttpExchange ex) throws IOException {
        String esperado = System.getenv("INTERNAL_API_TOKEN");
        if (esperado != null && !esperado.equals(ex.getRequestHeaders().getFirst("X-Internal-Token"))) {
            responder(ex, 401, "Token interno ausente ou inválido");
            return;
        }
        String metodo = ex.getRequestMethod();
        chamadas.add(metodo + " " + ex.getRequestURI().getPath());
        String[] p = ex.getRequestURI().getPath().substring("/internal/eventcaps/".length()).split("/");
        if ("batch".equals(p[0]) && "POST".equals(metodo)) {
            List<EventCap> achadas = new ArrayList<>();
            for (JsonNode id : JSON.readTree(ex.getRequestBody())) {
                EventCap c = caps.get(id.asLong());
                if (c != null) {
                    achadas.add(c);
                }
            }
            responder(ex, 200, JSON.writeValueAsString(achadas));
            return;
        }
        long id = Long.parseLong(p[0]);
        if (p.length == 1 && "PUT".equals(metodo)) {
            if (falharCriacao) {
                responder(ex, 500, "reservation indisponível (falha injetada)");
                return;
            }
            EventCap cap = JSON.readValue(ex.getRequestBody(), EventCap.class);
            cap.setEventId(id);
            cap.setQuant(cap.getCapacity());
            responder(ex, caps.putIfAbsent(id, cap) == null ? 201 : 200, null);
        } else if (p.length == 1 && "GET".equals(metodo)) {
            EventCap c = caps.get(id);
            responder(ex, c == null ? 404 : 200, c == null ? "sem eventcap" : JSON.writeValueAsString(c));
        } else if (p.length == 1 && "DELETE".equals(metodo)) {
            if (comReserva.contains(id)) {
                responder(ex, 409, "Evento possui reservas: não pode ser removido (id=" + id + ")");
                return;
            }
            caps.remove(id);
            responder(ex, 204, null);
        } else if (p.length == 2 && "PATCH".equals(metodo)) {
            if (statusPolitica != 0) {
                responder(ex, statusPolitica, "política recusada (falha injetada)");
                return;
            }
            EventCap nova = JSON.readValue(ex.getRequestBody(), EventCap.class);
            EventCap atual = caps.get(id);
            if (atual == null) {
                responder(ex, 404, "sem eventcap");
                return;
            }
            atual.setCapacity(nova.getCapacity());
            atual.setQuant(nova.getCapacity());
            atual.setBuyTimeLimit(nova.getBuyTimeLimit());
            responder(ex, 204, null);
        } else {
            responder(ex, 405, "método não suportado");
        }
    }

    private static void responder(HttpExchange ex, int status, String corpo) throws IOException {
        byte[] bytes = corpo == null ? new byte[0] : corpo.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0) {
            ex.getResponseHeaders().add("Content-Type", status < 300 ? "application/json" : "text/plain");
        }
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }
}
