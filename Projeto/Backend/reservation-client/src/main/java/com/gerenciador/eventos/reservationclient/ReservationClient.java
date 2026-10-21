package com.gerenciador.eventos.reservationclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gerenciador.eventos.reservationclient.api.EventCap;
import com.gerenciador.eventos.reservationclient.api.EventCapacityApi;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.reservationclient.api.EventWalletApi;
import com.gerenciador.eventos.reservationclient.api.IdempotencyConflictException;

/**
 * Cliente HTTP da API interna do serviço reservation (issue #33, Fase 7): é o salto de rede {@code gateway -> reservation}
 * que a Rodada 3 mede. Cada método de {@link EventWalletApi}/{@link EventCapacityApi} é uma (ou, no braço naive, duas)
 * chamada(s) síncrona(s); nada é cacheado nem repetido.
 *
 * <p><b>Pool de conexões</b> (Apache HttpClient 5, keep-alive): {@code maxConnections} no total E por rota (há uma só
 * rota, o serviço). Deve ser >= às threads do Tomcat do chamador (hoje 300), senão a fila se forma AQUI e não no
 * reservation, falseando a medição. O default de 5/20 do HttpClient serializaria a carga. Timeouts explícitos: conectar
 * ({@code connectTimeout}), esperar uma conexão livre do pool ({@code connectionRequestTimeout}) e esperar a resposta
 * ({@code responseTimeout}, também o timeout de leitura do socket). {@code evictIdle} fecha no cliente as conexões ociosas
 * ANTES de o servidor fechá-las ({@code server.tomcat.keep-alive-timeout} do reservation, 60 s): o evictor varre a cada
 * {@code evictIdle} e fecha o que está ocioso há mais que isso, então uma conexão fica ociosa no máximo ~2 x {@code evictIdle}
 * (20 s => 40 s < 60 s); senão a primeira requisição depois de uma pausa pegaria um socket já fechado pelo servidor e, sem
 * retry, viraria 5xx (que invalida uma repetição da matriz). Sem retry automático: um POST repetido
 * às cegas é decisão do chamador (a {@code Idempotency-Key} existe para isso).</p>
 *
 * <p><b>Erros</b> (o corpo da resposta de erro é a mensagem, texto puro): 400 => {@link IllegalArgumentException},
 * 422 => {@link IdempotencyConflictException}, 409 => {@link IllegalStateException}, 404 => depende do método,
 * qualquer outro => {@link RuntimeException}.</p>
 */
public class ReservationClient implements EventWalletApi, EventCapacityApi {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    /** Resposta de {@code GET /internal/events/{id}/availability}. */
    public record Disponibilidade(int available) { }

    private final RestClient http;
    private final CloseableHttpClient httpClient;

    public ReservationClient(String baseUrl, int maxConnections, Duration connectTimeout,
            Duration connectionRequestTimeout, Duration responseTimeout, Duration evictIdle) {
        PoolingHttpClientConnectionManager pool = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(maxConnections)
                .setMaxConnPerRoute(maxConnections)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                        .setSocketTimeout(Timeout.ofMilliseconds(responseTimeout.toMillis()))
                        .build())
                .build();
        this.httpClient = HttpClients.custom()
                .setConnectionManager(pool)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectionRequestTimeout.toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(responseTimeout.toMillis()))
                        .build())
                .evictIdleConnections(TimeValue.ofMilliseconds(evictIdle.toMillis()))
                .evictExpiredConnections()
                .disableAutomaticRetries()
                .build();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .messageConverters(c -> {
                    c.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                    c.add(0, new MappingJackson2HttpMessageConverter(mapper));
                })
                .defaultStatusHandler(HttpStatusCode::isError, (req, resp) -> {
                    throw erro(resp.getStatusCode().value(),
                            new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8));
                })
                .build();
    }

    /** Fecha o pool (destroyMethod do bean; os testes também). */
    public void close() throws IOException {
        httpClient.close();
    }

    // ===================== EventWalletApi =====================

    @Override
    public EventWallet addLink(EventWallet ew, String idempotencyKey, boolean naive) {
        boolean chaveDoCliente = idempotencyKey != null && !idempotencyKey.isBlank();
        if (naive) {
            // Chamada 1 do braço naive: a disponibilidade, lida no serviço (projeção assíncrona). Chamada 2: a reserva.
            // A janela check-then-act é a ida e volta entre elas.
            Integer disponivel = disponibilidade(ew.getEventId());
            if (disponivel == null) {
                throw new IllegalArgumentException("Capacidade do evento não configurada");
            }
            if (disponivel == 0) {
                // Repetição de uma reserva que já vale: o resultado certo é o salvo, não "lotado" (só com chave do cliente).
                if (chaveDoCliente) {
                    EventWallet salvo = replay(ew, idempotencyKey);
                    if (salvo != null) {
                        return salvo;
                    }
                }
                throw new IllegalArgumentException("Evento lotado");
            }
        }
        ResponseEntity<EventWallet> resp = http.post()
                .uri(b -> b.path("/internal/reservations").queryParam("path", naive ? "naive" : "cas").build())
                .headers(h -> {
                    if (chaveDoCliente) {
                        h.set("Idempotency-Key", idempotencyKey);
                    }
                })
                .contentType(JSON)
                .body(corpo(ew.getUserId(), ew.getEventId()))
                .retrieve().toEntity(EventWallet.class);
        EventWallet saved = resp.getBody();
        saved.setIdempotencyKey(resp.getHeaders().getFirst("Idempotency-Key"));
        return saved;
    }

    /** Disponibilidade defasada (braço naive): {@code null} se o evento não tem eventcap; {@code -1} = ilimitado. */
    public Integer disponibilidade(Long eventId) {
        try {
            return http.get().uri("/internal/events/{id}/availability", eventId)
                    .retrieve().body(Disponibilidade.class).available();
        } catch (NaoEncontrado ex) {
            return null;
        }
    }

    /** Resultado salvo da reserva com a chave (200), ou {@code null}; chave de outro evento => 422. */
    private EventWallet replay(EventWallet ew, String idempotencyKey) {
        try {
            return http.get()
                    .uri(b -> b.path("/internal/reservations/replay").queryParam("userId", ew.getUserId())
                            .queryParam("eventId", ew.getEventId()).queryParam("idempotencyKey", idempotencyKey).build())
                    .retrieve().body(EventWallet.class);
        } catch (NaoEncontrado ex) {
            return null;
        }
    }

    @Override
    public EventWallet confirm(Long userId, Long eventId) {
        return http.post().uri("/internal/reservations/confirm").contentType(JSON)
                .body(corpo(userId, eventId)).retrieve().body(EventWallet.class);
    }

    @Override
    public void removeLink(Long userId, Long eventId) {
        http.delete().uri(b -> b.path("/internal/reservations").queryParam("userId", userId)
                .queryParam("eventId", eventId).build()).retrieve().toBodilessEntity();
    }

    @Override
    public List<EventWallet> listByUser(Long userId) {
        return http.get().uri("/internal/reservations/user/{userId}", userId).retrieve()
                .body(new ParameterizedTypeReference<List<EventWallet>>() { });
    }

    @Override
    public boolean existsActive(Long userId, Long eventId) {
        Boolean ativa = http.get().uri(b -> b.path("/internal/reservations/active").queryParam("userId", userId)
                .queryParam("eventId", eventId).build()).retrieve().body(Boolean.class);
        return Boolean.TRUE.equals(ativa);
    }

    // ===================== EventCapacityApi =====================

    @Override
    public void create(EventCap cap) {
        http.put().uri("/internal/eventcaps/{id}", cap.getEventId()).contentType(JSON).body(cap)
                .retrieve().toBodilessEntity();
    }

    @Override
    public EventCap findByEventId(Long eventId) {
        try {
            return http.get().uri("/internal/eventcaps/{id}", eventId).retrieve().body(EventCap.class);
        } catch (NaoEncontrado ex) {
            return null;
        }
    }

    @Override
    public Map<Long, EventCap> findByEventIds(Collection<Long> eventIds) {
        Map<Long, EventCap> out = new HashMap<>();
        if (eventIds == null || eventIds.isEmpty()) {
            return out;
        }
        // ids no CORPO (POST): a busca de eventos hidrata a lista inteira (milhares de ids) e a query string estouraria o
        // limite de header do Tomcat do reservation
        List<EventCap> caps = http.post().uri("/internal/eventcaps/batch").contentType(JSON).body(List.copyOf(eventIds))
                .retrieve().body(new ParameterizedTypeReference<List<EventCap>>() { });
        if (caps != null) {
            caps.forEach(c -> out.put(c.getEventId(), c));
        }
        return out;
    }

    @Override
    public boolean updatePolicy(Long eventId, Integer newCapacity, LocalDateTime buyTimeLimit) {
        EventCap politica = new EventCap();
        politica.setEventId(eventId);
        politica.setCapacity(newCapacity);
        politica.setBuyTimeLimit(buyTimeLimit);
        try {
            http.patch().uri("/internal/eventcaps/{id}/policy", eventId).contentType(JSON).body(politica)
                    .retrieve().toBodilessEntity();
            return true;
        } catch (NaoEncontrado ex) {
            return false;
        }
    }

    @Override
    public void delete(Long eventId) {
        http.delete().uri("/internal/eventcaps/{id}", eventId).retrieve().toBodilessEntity();
    }

    // ===================== erros =====================

    private static Map<String, Long> corpo(Long userId, Long eventId) {
        Map<String, Long> m = new HashMap<>();
        m.put("userId", userId);
        m.put("eventId", eventId);
        return m;
    }

    private static RuntimeException erro(int status, String mensagem) {
        return switch (status) {
            case 400 -> new IllegalArgumentException(mensagem);
            case 404 -> new NaoEncontrado(mensagem);
            case 409 -> new IllegalStateException(mensagem);
            case 422 -> new IdempotencyConflictException(mensagem);
            default -> new RuntimeException("Serviço reservation respondeu HTTP " + status + ": " + mensagem);
        };
    }

    /** 404 do serviço; cada método decide se vira {@code null} ou erro. */
    private static final class NaoEncontrado extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NaoEncontrado(String mensagem) {
            super(mensagem);
        }
    }
}
