package com.gerenciador.eventos.TestSupport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gerenciador.eventos.catalogclient.api.Event;
import com.gerenciador.eventos.catalogclient.api.EventoNaoEncontradoException;
import com.gerenciador.eventos.stockclient.api.EventCap;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Servidor HTTP de verdade (JDK {@code HttpServer}, porta sorteada) no papel do serviço catalog nos testes do MONÓLITO (issue
 * #35). O monólito não tem o código do serviço no classpath (fronteira física), então o gateway fala HTTP com este servidor, em
 * memória, pelo {@code CatalogClient} real. Implementa o MESMO contrato do {@code EventController} do serviço
 * ({@code /internal/events/**}: rotas, status, corpo de erro em texto, 404 com o cabeçalho
 * {@link EventoNaoEncontradoException#HEADER}, 401 sem o token, 409 apagar com reserva, DELETE de inexistente = 204, imagem em
 * bytes e fora das listas) e as regras mínimas que os testes do gateway enxergam (validação com as mensagens do serviço, nome
 * único, {@code buy_time_limit} default, apresentadores normalizados). A capacidade é a do {@link FakeReservationServer} (o
 * catalog real a cria/atualiza/remove por HTTP no reservation; aqui, direto no estado dele) e hidrata {@code lot_quantity}/
 * {@code quantity}.
 *
 * <p>É um fake: event sourcing, Postgres e o 23505 do nome são do módulo {@code catalog}, e o contrato cliente REAL x serviço
 * REAL é o {@code CatalogApiE2ETest} de lá. Uma instância por JVM, iniciada pelo {@link CatalogFakeSession} (URL na propriedade
 * {@code catalog.service.url}); {@link #foraDoAr(boolean)} simula o container parado (500 em toda rota: o cliente lança
 * {@code CatalogUnavailableException}).</p>
 */
public final class FakeCatalogServer {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static FakeCatalogServer instancia;

    public static synchronized FakeCatalogServer iniciar() throws IOException {
        if (instancia == null) {
            instancia = new FakeCatalogServer();
        }
        return instancia;
    }

    public static FakeCatalogServer get() {
        return instancia;
    }

    private final HttpServer server;
    private final Map<Long, Event> eventos = new ConcurrentHashMap<>();
    private final Map<Long, byte[]> imagens = new ConcurrentHashMap<>();
    private final AtomicLong sequencia = new AtomicLong();
    private final List<String> chamadas = new CopyOnWriteArrayList<>();
    private volatile boolean foraDoAr;

    private FakeCatalogServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/events", this::tratar);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Esvazia o estado (o {@code GlobalDbTruncator} chama junto com o TRUNCATE). A sequência de id NÃO volta (como a identity). */
    public synchronized void reset() {
        eventos.clear();
        imagens.clear();
        chamadas.clear();
        foraDoAr = false;
    }

    /** O "container parado": toda rota responde 500. */
    public void foraDoAr(boolean fora) {
        this.foraDoAr = fora;
    }

    /** "METODO /caminho" de cada chamada recebida, na ordem. */
    public List<String> chamadas() {
        return Collections.unmodifiableList(new ArrayList<>(chamadas));
    }

    public int quantidadeDeEventos() {
        return eventos.size();
    }

    /** Grava um evento com o id dado, sem passar pela API (o teste do cache negativo do {@code exists}). */
    public void gravarComId(long id, long criador, String nome) {
        Event e = new Event();
        e.setEvent_id(id);
        e.setCreator_id(criador);
        e.setEvent_name(nome);
        e.setIs_EAD(true);
        e.setEvent_date(LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS));
        e.setDescription("desc");
        eventos.put(id, e);
    }

    public void apagar(long id) {
        eventos.remove(id);
        imagens.remove(id);
    }

    // ===================== roteamento (espelha o EventController do serviço) =====================

    private void tratar(HttpExchange ex) throws IOException {
        String metodo = ex.getRequestMethod();
        String caminho = ex.getRequestURI().getPath();
        String esperado = System.getenv("INTERNAL_API_TOKEN");
        if (esperado != null && !esperado.equals(ex.getRequestHeaders().getFirst("X-Internal-Token"))) {
            chamadas.add("401 " + metodo + " " + caminho);
            responder(ex, 401, "text/plain", "Token interno ausente ou inválido");
            return;
        }
        chamadas.add(metodo + " " + caminho);
        if (foraDoAr) {
            responder(ex, 500, "text/plain", "Erro interno no serviço catalog");
            return;
        }
        try {
            String resto = caminho.substring("/internal/events".length());
            String[] p = resto.isEmpty() ? new String[0] : resto.substring(1).split("/");
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            if (p.length == 0) {
                raiz(ex, metodo, q);
            } else if ("validate".equals(p[0])) {
                responder(ex, 200, "text/plain", erros(ler(ex)));
            } else if ("search".equals(p[0])) {
                String termo = q.getOrDefault("term", "").toLowerCase();
                responderJson(ex, 200, lista(e -> e.getEvent_name().toLowerCase().contains(termo)));
            } else {
                porId(ex, metodo, Long.parseLong(p[0]), p.length > 1 ? p[1] : "");
            }
        } catch (IllegalArgumentException e) {
            responder(ex, 400, "text/plain", e.getMessage());
        } catch (NaoEncontrado e) {
            ex.getResponseHeaders().add(EventoNaoEncontradoException.HEADER, "true");
            responder(ex, 404, "text/plain", e.getMessage());
        } catch (Conflito e) {
            responder(ex, 409, "text/plain", e.getMessage());
        } catch (RuntimeException e) {
            responder(ex, 500, "text/plain", "Erro interno no serviço catalog");
        }
    }

    private void raiz(HttpExchange ex, String metodo, Map<String, String> q) throws IOException {
        if ("POST".equals(metodo)) {
            responderJson(ex, 201, criar(ler(ex)));
        } else if ("GET".equals(metodo)) {
            long criador = Long.parseLong(q.get("creatorId"));
            responderJson(ex, 200, lista(e -> e.getCreator_id() != null && e.getCreator_id() == criador));
        } else {
            responder(ex, 405, "text/plain", "método não suportado");
        }
    }

    private void porId(HttpExchange ex, String metodo, long id, String sub) throws IOException {
        Event atual = eventos.get(id);
        switch (sub + ":" + metodo) {
            case ":GET" -> responderJson(ex, 200, hidratar(existente(atual, id), true));
            case ":PUT" -> responderJson(ex, 200, atualizar(id, ler(ex)));
            case ":DELETE" -> {
                if (atual != null) {
                    if (!FakeReservationServer.get().removerCapacidade(id)) {
                        throw new Conflito("Evento possui reservas: não pode ser removido (id=" + id + ")");
                    }
                    apagar(id);
                }
                responder(ex, 204, null, null);
            }
            case "exists:GET" -> responderJson(ex, 200, atual != null);
            case "creator:GET" -> responderJson(ex, 200, existente(atual, id).getCreator_id());
            case "image:GET" -> {
                byte[] img = imagens.get(id);
                if (atual == null || img == null || img.length == 0) {
                    throw new NaoEncontrado("Evento não encontrado ou sem imagem: id=" + id);
                }
                ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
                ex.sendResponseHeaders(200, img.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(img);
                }
                ex.close();
            }
            case "image:POST" -> {
                byte[] img = ex.getRequestBody().readAllBytes();
                if (img.length == 0) {
                    throw new IllegalArgumentException("Imagem vazia");
                }
                existente(atual, id);
                imagens.put(id, img);
                responder(ex, 204, null, null);
            }
            default -> responder(ex, 405, "text/plain", "método não suportado");
        }
    }

    // ===================== regras (mesmas mensagens do EventService do serviço) =====================

    private synchronized Event criar(Event e) {
        regras(e, null);
        long id = sequencia.incrementAndGet();
        e.setEvent_id(id);
        e.setPresenters(normalizar(e.getPresenters()));
        LocalDateTime agora = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        e.setCreatedAt(agora);
        e.setUpdatedAt(agora);
        e.setImage_data(null);
        eventos.put(id, e);
        FakeReservationServer.get().criarCapacidade(id, e.getLot_quantity(), e.getBuy_time_limit());
        return hidratar(e, false);
    }

    private synchronized Event atualizar(long id, Event e) {
        if (e.getEvent_id() == null) {
            e.setEvent_id(id);
        } else if (e.getEvent_id() != id) {
            throw new IllegalArgumentException("ID do path difere do body");
        }
        Event atual = existente(eventos.get(id), id);
        regras(e, id);
        e.setPresenters(e.getPresenters() == null ? atual.getPresenters() : normalizar(e.getPresenters()));
        e.setCreatedAt(atual.getCreatedAt());
        e.setUpdatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        e.setImage_data(null);
        eventos.put(id, e);
        if (e.getLot_quantity() != null) {
            FakeReservationServer.get().atualizarCapacidade(id, e.getLot_quantity(), e.getBuy_time_limit());
        }
        return hidratar(e, false);
    }

    private void regras(Event e, Long idAtual) {
        if (e.getBuy_time_limit() == null && e.getEvent_date() != null) {
            e.setBuy_time_limit(e.getEvent_date());
        }
        if (e.getCreator_id() == null) throw new IllegalArgumentException("creator_id é obrigatório");
        if (e.getEvent_name() == null || e.getEvent_name().isBlank()) {
            throw new IllegalArgumentException("Nome do evento é obrigatório");
        }
        if (e.getIs_EAD() == null) throw new IllegalArgumentException("is_EAD deve ser true/false");
        if (Boolean.FALSE.equals(e.getIs_EAD()) && (e.getAddress() == null || e.getAddress().isBlank())) {
            throw new IllegalArgumentException("Endereço é obrigatório para presencial");
        }
        if (e.getEvent_date() == null) throw new IllegalArgumentException("Data do evento é obrigatória");
        if (e.getBuy_time_limit().isAfter(e.getEvent_date())) {
            throw new IllegalArgumentException("buy_time_limit não pode ser após event_date");
        }
        if (!capacidadeValida(e.getLot_quantity())) {
            throw new IllegalArgumentException("capacity (lot_quantity) é obrigatória: >= 1, ou -1 para ilimitado");
        }
        boolean nomeOcupado = eventos.values().stream()
                .anyMatch(o -> o.getEvent_name().equals(e.getEvent_name()) && !o.getEvent_id().equals(idAtual));
        if (nomeOcupado) throw new IllegalArgumentException("Nome de evento já existente: " + e.getEvent_name());
    }

    /** O {@code getValidationErrors} do serviço (texto; vazio = válido). */
    private String erros(Event e) {
        StringBuilder sb = new StringBuilder();
        if (e.getBuy_time_limit() == null && e.getEvent_date() != null) e.setBuy_time_limit(e.getEvent_date());
        if (e.getCreator_id() == null) sb.append("creator_id é obrigatório. ");
        if (e.getEvent_name() == null || e.getEvent_name().isBlank()) sb.append("Nome do evento é obrigatório. ");
        if (e.getIs_EAD() == null) sb.append("is_EAD deve ser true/false. ");
        if (Boolean.FALSE.equals(e.getIs_EAD()) && (e.getAddress() == null || e.getAddress().isBlank())) {
            sb.append("Endereço é obrigatório para presencial. ");
        }
        if (e.getEvent_date() == null) sb.append("Data do evento é obrigatória. ");
        if (e.getBuy_time_limit() != null && e.getEvent_date() != null && e.getBuy_time_limit().isAfter(e.getEvent_date())) {
            sb.append("buy_time_limit não pode ser após event_date. ");
        }
        if (!capacidadeValida(e.getLot_quantity())) {
            sb.append("capacity (lot_quantity) é obrigatória: >= 1, ou -1 para ilimitado. ");
        }
        if (e.getEvent_name() != null && eventos.values().stream().anyMatch(o -> o.getEvent_name().equals(e.getEvent_name()))) {
            sb.append("Nome de evento já existente: ").append(e.getEvent_name()).append(". ");
        }
        return sb.toString().trim();
    }

    private static boolean capacidadeValida(Integer c) {
        return c != null && (c >= 1 || c == EventCap.UNLIMITED);
    }

    private static List<String> normalizar(List<String> nomes) {
        TreeSet<String> unicos = new TreeSet<>();
        for (String n : nomes == null ? List.<String>of() : nomes) {
            if (n != null && !n.isBlank()) unicos.add(n.trim());
        }
        return new ArrayList<>(unicos);
    }

    private static Event existente(Event e, long id) {
        if (e == null) throw new NaoEncontrado("Evento não encontrado: id=" + id);
        return e;
    }

    /** Cópia com a capacidade do reservation falso; {@code image_data} só no detalhe (fora das listas). */
    private Event hidratar(Event e, boolean comImagem) {
        Event c = new Event(e.getEvent_id(), e.getCreator_id(), e.getEvent_name(), e.getIs_EAD(), e.getAddress(),
                e.getEvent_date(), e.getBuy_time_limit(), e.getLot_quantity(), 0, e.getDescription(),
                new ArrayList<>(e.getPresenters() == null ? List.of() : e.getPresenters()));
        c.setCreatedAt(e.getCreatedAt());
        c.setUpdatedAt(e.getUpdatedAt());
        EventCap cap = FakeReservationServer.get().capacidade(e.getEvent_id());
        if (cap != null) {
            c.setLot_quantity(cap.getCapacity());
            c.setQuantity(cap.getQuant() != null ? cap.getQuant() : 0);
            c.setBuy_time_limit(cap.getBuyTimeLimit());
        }
        if (comImagem) c.setImage_data(imagens.get(e.getEvent_id()));
        return c;
    }

    private List<Event> lista(java.util.function.Predicate<Event> filtro) {
        return eventos.values().stream().filter(filtro).sorted(Comparator.comparing(Event::getEvent_id))
                .map(e -> hidratar(e, false)).toList();
    }

    // ===================== http =====================

    private static Event ler(HttpExchange ex) throws IOException {
        return JSON.readValue(ex.getRequestBody(), Event.class);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String par : raw.split("&")) {
            String[] kv = par.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
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

    private static final class NaoEncontrado extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NaoEncontrado(String m) {
            super(m);
        }
    }

    private static final class Conflito extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Conflito(String m) {
            super(m);
        }
    }
}
