package com.gerenciador.eventos.TestSupport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gerenciador.eventos.reservationclient.api.EventCap;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Servidor HTTP de verdade (JDK {@code HttpServer}, porta sorteada) que faz o papel do serviço reservation nos testes do
 * MONÓLITO (issue #33). O monólito não tem o código do serviço no classpath (fronteira física), então os testes dele falam
 * HTTP com este servidor, em memória, que implementa o MESMO contrato da API interna e a lógica mínima para os testes do
 * gateway e do catalog (capacidade idempotente por evento, CAS contando reservas, {@code Idempotency-Key} por usuário,
 * recusa de apagar capacidade com reserva, janela de compra, mensagens do confirm, PATCH sem eventcap => 404, disponibilidade
 * defasada controlável). Alinhado ao serviço real nessas divergências; ainda assim é um fake: o CAS aqui é uma seção crítica
 * em memória. A lógica de verdade (CAS no Postgres,
 * event sourcing, replay) é testada no módulo {@code reservation}, e o contrato cliente-servidor pelo
 * {@code ReservationApiE2ETest} de lá, com o cliente real contra o serviço real.
 *
 * <p>Uma instância por JVM de teste, iniciada pelo {@link ReservationFakeSession} antes de qualquer contexto Spring (a URL vai
 * na propriedade de sistema {@code reservation.service.url}). Cada chamada fica registrada em {@link #chamadas()} para os
 * testes provarem o que o gateway pediu e em que ordem.</p>
 */
public final class FakeReservationServer {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static FakeReservationServer instancia;

    public static synchronized FakeReservationServer iniciar() throws IOException {
        if (instancia == null) {
            instancia = new FakeReservationServer();
        }
        return instancia;
    }

    public static FakeReservationServer get() {
        return instancia;
    }

    private final HttpServer server;
    private final Map<Long, EventCap> caps = new ConcurrentHashMap<>();
    private final Map<String, EventWallet> reservas = new ConcurrentHashMap<>(); // "user:event"
    private final Map<String, Long> chaves = new ConcurrentHashMap<>(); // "user:chave" -> eventId
    private final Map<String, LocalDateTime> chaveExpira = new ConcurrentHashMap<>();
    private final Map<Long, Integer> projecao = new ConcurrentHashMap<>(); // ativos "vistos" pelo consumer (defasado)
    private final List<String> chamadas = new CopyOnWriteArrayList<>();
    private volatile boolean falharCriacaoDeCapacidade;

    private FakeReservationServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal", this::tratar);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(16));
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Esvazia o estado (o {@code GlobalDbTruncator} chama junto com o TRUNCATE: ids de evento voltam a 1). */
    public synchronized void reset() {
        caps.clear();
        reservas.clear();
        chaves.clear();
        chaveExpira.clear();
        projecao.clear();
        chamadas.clear();
    }

    /** "METODO /caminho" de cada chamada recebida, na ordem. */
    public List<String> chamadas() {
        return Collections.unmodifiableList(new ArrayList<>(chamadas));
    }

    public void limparChamadas() {
        chamadas.clear();
    }

    /** O que o "consumer" já aplicou na projeção de disponibilidade do evento (default 0: consumer parado). */
    public void projetar(long eventId, int ativos) {
        projecao.put(eventId, ativos);
    }

    /** Faz o {@code PUT /internal/eventcaps/{id}} responder 500 (o serviço "fora do ar" para a criação do evento). */
    public void falharCriacaoDeCapacidade(boolean falhar) {
        this.falharCriacaoDeCapacidade = falhar;
    }

    public int reservasAtivas(long eventId) {
        return (int) reservas.values().stream().filter(r -> r.getEventId() == eventId && ativa(r)).count();
    }

    public EventCap capacidade(long eventId) {
        return caps.get(eventId);
    }

    // ===== chamadas em memória do FakeCatalogServer (issue #35): o catalog real fala HTTP com o reservation; o falso do
    // catalog mexe direto no estado deste, com as mesmas regras das rotas /internal/eventcaps abaixo =====

    /** PUT idempotente por {@code event_id}; {@code false} se a criação está "fora do ar" (o catalog real segue sem eventcap). */
    public boolean criarCapacidade(long eventId, Integer capacity, LocalDateTime buyTimeLimit) {
        if (falharCriacaoDeCapacidade) {
            return false;
        }
        EventCap cap = new EventCap(eventId, capacity, capacity, buyTimeLimit);
        cap.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        caps.putIfAbsent(eventId, cap);
        return true;
    }

    /** PATCH da política; sem eventcap cria com os valores novos (como o catalog real faz depois do 404). */
    public void atualizarCapacidade(long eventId, Integer capacity, LocalDateTime buyTimeLimit) {
        EventCap atual = caps.get(eventId);
        if (atual == null) {
            criarCapacidade(eventId, capacity, buyTimeLimit);
            return;
        }
        int vendidas = reservasAtivas(eventId);
        if (capacity != -1 && capacity < vendidas) {
            throw new IllegalArgumentException("Capacidade menor que o número de inscrições já feitas");
        }
        atual.setCapacity(capacity);
        atual.setQuant(capacity == -1 ? -1 : capacity - vendidas);
        atual.setBuyTimeLimit(buyTimeLimit);
    }

    /** DELETE da capacidade; {@code false} = recusado (o evento tem reserva, o 409 do serviço real). */
    public boolean removerCapacidade(long eventId) {
        if (reservas.values().stream().anyMatch(r -> r.getEventId() == eventId)) {
            return false;
        }
        caps.remove(eventId);
        return true;
    }

    // ===================== roteamento =====================

    private void tratar(HttpExchange ex) throws IOException {
        String metodo = ex.getRequestMethod();
        String caminho = ex.getRequestURI().getPath();
        String esperado = System.getenv("INTERNAL_API_TOKEN");
        if (esperado != null && !esperado.equals(ex.getRequestHeaders().getFirst("X-Internal-Token"))) {
            chamadas.add("401 " + metodo + " " + caminho); // como o serviço real: sem o token de serviço, 401
            responder(ex, 401, "text/plain", "Token interno ausente ou inválido");
            return;
        }
        chamadas.add(metodo + " " + caminho);
        try {
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String[] p = caminho.substring("/internal/".length()).split("/");
            switch (p[0]) {
                case "eventcaps" -> eventcaps(ex, metodo, p, q);
                case "events" -> disponibilidade(ex, Long.parseLong(p[1]));
                case "reservations" -> reservations(ex, metodo, p, q);
                default -> responder(ex, 404, "text/plain", "rota desconhecida");
            }
        } catch (IllegalArgumentException e) {
            responder(ex, 400, "text/plain", e.getMessage());
        } catch (ConflitoDeChave e) {
            responder(ex, 422, "text/plain", e.getMessage());
        } catch (ConflitoDeReservas e) {
            responder(ex, 409, "text/plain", e.getMessage());
        } catch (RuntimeException e) {
            responder(ex, 500, "text/plain", String.valueOf(e.getMessage()));
        }
    }

    private void eventcaps(HttpExchange ex, String metodo, String[] p, Map<String, String> q) throws IOException {
        if (p.length == 2 && "batch".equals(p[1]) && "POST".equals(metodo)) { // ids no CORPO, como no serviço real
            List<EventCap> achadas = new ArrayList<>();
            for (JsonNode id : JSON.readTree(ex.getRequestBody())) {
                EventCap c = caps.get(id.asLong());
                if (c != null) achadas.add(copia(c));
            }
            responderJson(ex, 200, achadas);
            return;
        }
        long id = Long.parseLong(p[1]);
        if (p.length == 2 && "PUT".equals(metodo)) {
            if (falharCriacaoDeCapacidade) {
                throw new RuntimeException("reservation indisponível (falha injetada)");
            }
            EventCap cap = JSON.readValue(ex.getRequestBody(), EventCap.class);
            cap.setEventId(id);
            if (cap.getCapacity() == null || (cap.getCapacity() < 1 && cap.getCapacity() != -1)) {
                throw new IllegalArgumentException("capacity é obrigatória: >= 1, ou -1 para ilimitado");
            }
            cap.setQuant(cap.getCapacity());
            cap.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
            boolean criou = caps.putIfAbsent(id, cap) == null;
            responder(ex, criou ? 201 : 200, null, null);
        } else if (p.length == 2 && "GET".equals(metodo)) {
            EventCap c = caps.get(id);
            if (c == null) responder(ex, 404, "text/plain", "sem eventcap"); else responderJson(ex, 200, copia(c));
        } else if (p.length == 2 && "DELETE".equals(metodo)) {
            if (reservas.values().stream().anyMatch(r -> r.getEventId() == id)) {
                throw new ConflitoDeReservas("Evento possui reservas: não pode ser removido (id=" + id + ")");
            }
            caps.remove(id);
            responder(ex, 204, null, null);
        } else if (p.length == 3 && "PATCH".equals(metodo)) {
            EventCap nova = JSON.readValue(ex.getRequestBody(), EventCap.class);
            EventCap atual = caps.get(id);
            if (atual == null) { // como o serviço real: sem eventcap, nada foi atualizado
                responder(ex, 404, "text/plain", "sem eventcap");
                return;
            }
            int vendidas = reservasAtivas(id);
            if (nova.getCapacity() != -1 && nova.getCapacity() < vendidas) {
                throw new IllegalArgumentException("Capacidade menor que o número de inscrições já feitas");
            }
            atual.setCapacity(nova.getCapacity());
            atual.setQuant(nova.getCapacity() == -1 ? -1 : nova.getCapacity() - vendidas);
            atual.setBuyTimeLimit(nova.getBuyTimeLimit());
            responder(ex, 204, null, null);
        } else {
            responder(ex, 405, "text/plain", "método não suportado");
        }
    }

    private void disponibilidade(HttpExchange ex, long eventId) throws IOException {
        EventCap c = caps.get(eventId);
        if (c == null) {
            responder(ex, 404, "text/plain", "sem eventcap");
            return;
        }
        int disp = c.getCapacity() == -1 ? -1 : Math.max(c.getCapacity() - Math.max(projecao.getOrDefault(eventId, 0), 0), 0);
        responderJson(ex, 200, Map.of("available", disp));
    }

    private void reservations(HttpExchange ex, String metodo, String[] p, Map<String, String> q) throws IOException {
        if (p.length == 1 && "POST".equals(metodo)) {
            JsonNode corpo = JSON.readTree(ex.getRequestBody());
            long userId = corpo.get("userId").asLong();
            long eventId = corpo.get("eventId").asLong();
            String chave = ex.getRequestHeaders().getFirst("Idempotency-Key");
            if (chave == null || chave.isBlank()) chave = UUID.randomUUID().toString();
            if (chave.length() > 255) throw new IllegalArgumentException("Idempotency-Key deve ter no máximo 255 caracteres");
            EventWallet salva = salvo(userId, eventId, chave);
            if (salva != null) {
                ex.getResponseHeaders().add("Idempotency-Key", chave);
                responderJson(ex, 201, salva);
                return;
            }
            EventCap cap = caps.get(eventId);
            if (cap == null) throw new IllegalArgumentException("Capacidade do evento não configurada");
            if (cap.getBuyTimeLimit() != null && LocalDateTime.now().isAfter(cap.getBuyTimeLimit())) {
                throw new IllegalArgumentException("Janela de compra encerrada para este evento");
            }
            EventWallet resposta;
            synchronized (this) { // o CAS do serviço real é atômico: checar a capacidade e gravar não podem se intercalar
                EventWallet atual = reservas.get(userId + ":" + eventId);
                if (atual != null && ativa(atual)) {
                    EventWallet salvaDepois = salvo(userId, eventId, chave);
                    if (salvaDepois == null) throw new IllegalArgumentException("Vínculo já existente");
                    resposta = salvaDepois;
                } else {
                    if ("cas".equals(q.getOrDefault("path", "cas")) && cap.getCapacity() != -1
                            && reservasAtivas(eventId) >= cap.getCapacity()) {
                        throw new IllegalArgumentException("Evento lotado");
                    }
                    resposta = new EventWallet(userId, eventId);
                    resposta.setStatus("pending");
                    resposta.setExpiresAt(LocalDateTime.now().plusSeconds(120).truncatedTo(ChronoUnit.MICROS));
                    reservas.put(userId + ":" + eventId, resposta);
                    chaves.put(userId + ":" + chave, eventId);
                    chaveExpira.put(userId + ":" + chave, resposta.getExpiresAt());
                }
            }
            ex.getResponseHeaders().add("Idempotency-Key", chave);
            responderJson(ex, 201, resposta);
        } else if (p.length == 2 && "replay".equals(p[1])) {
            EventWallet salva = salvo(Long.parseLong(q.get("userId")), Long.parseLong(q.get("eventId")), q.get("idempotencyKey"));
            if (salva == null) responder(ex, 404, "text/plain", "sem chave"); else responderJson(ex, 200, salva);
        } else if (p.length == 2 && "confirm".equals(p[1])) {
            JsonNode corpo = JSON.readTree(ex.getRequestBody());
            EventWallet r = reservas.get(corpo.get("userId").asLong() + ":" + corpo.get("eventId").asLong());
            if (r == null || !"pending".equals(r.getStatus())) { // mesmas mensagens do serviço real
                String status = r == null ? null : r.getStatus();
                if ("confirmed".equals(status)) throw new IllegalArgumentException("Reserva já confirmada");
                if ("expired".equals(status)) throw new IllegalArgumentException("Reserva expirada — refaça a inscrição");
                throw new IllegalArgumentException("Nenhuma reserva pendente para confirmar");
            }
            r.setStatus("confirmed");
            r.setExpiresAt(null);
            responderJson(ex, 200, r);
        } else if (p.length == 1 && "DELETE".equals(metodo)) {
            EventWallet r = reservas.get(q.get("userId") + ":" + q.get("eventId"));
            if (r != null && ativa(r)) r.setStatus("cancelled");
            responder(ex, 200, null, null);
        } else if (p.length == 3 && "user".equals(p[1])) {
            long userId = Long.parseLong(p[2]);
            responderJson(ex, 200, reservas.values().stream().filter(r -> r.getUserId() == userId).collect(Collectors.toList()));
        } else if (p.length == 2 && "active".equals(p[1])) {
            EventWallet r = reservas.get(q.get("userId") + ":" + q.get("eventId"));
            responderJson(ex, 200, r != null && ativa(r));
        } else {
            responder(ex, 405, "text/plain", "método não suportado");
        }
    }

    /** Resultado salvo da chave do usuário: a reserva original como {@code pending} (a API devolve sempre assim). */
    private EventWallet salvo(long userId, long eventId, String chave) {
        Long ev = chaves.get(userId + ":" + chave);
        if (ev == null) return null;
        if (ev != eventId) throw new ConflitoDeChave("Idempotency-Key já usada em outra requisição (evento diferente)");
        EventWallet w = new EventWallet(userId, eventId);
        w.setStatus("pending");
        w.setExpiresAt(chaveExpira.get(userId + ":" + chave));
        return w;
    }

    private static boolean ativa(EventWallet r) {
        return "pending".equals(r.getStatus()) || "confirmed".equals(r.getStatus());
    }

    private static EventCap copia(EventCap c) {
        EventCap o = new EventCap(c.getEventId(), c.getCapacity(), c.getQuant(), c.getBuyTimeLimit());
        o.setMaxTimeInLine(c.getMaxTimeInLine());
        return o;
    }

    // ===================== http =====================

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String par : raw.split("&")) {
            String[] kv = par.split("=", 2);
            m.put(java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }

    private static void responderJson(HttpExchange ex, int status, Object corpo) throws IOException {
        responder(ex, status, "application/json", JSON.writeValueAsString(corpo));
    }

    private static void responder(HttpExchange ex, int status, String tipo, String corpo) throws IOException {
        byte[] bytes = corpo == null ? new byte[0] : corpo.getBytes(StandardCharsets.UTF_8);
        if (tipo != null) ex.getResponseHeaders().add("Content-Type", tipo);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }

    private static final class ConflitoDeChave extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ConflitoDeChave(String m) {
            super(m);
        }
    }

    private static final class ConflitoDeReservas extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ConflitoDeReservas(String m) {
            super(m);
        }
    }
}
