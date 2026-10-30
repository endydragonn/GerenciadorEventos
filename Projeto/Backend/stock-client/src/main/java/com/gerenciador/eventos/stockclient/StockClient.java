package com.gerenciador.eventos.stockclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gerenciador.eventos.clientlb.ClientLb;
import com.gerenciador.eventos.stockclient.api.EventCap;
import com.gerenciador.eventos.stockclient.api.EventCapacityApi;
import com.gerenciador.eventos.stockclient.api.HoldLiberadoException;
import com.gerenciador.eventos.stockclient.api.HoldRequest;
import com.gerenciador.eventos.stockclient.api.HoldResponse;
import com.gerenciador.eventos.stockclient.api.ReleaseRequest;
import com.gerenciador.eventos.stockclient.api.StockConflictException;

/**
 * Cliente HTTP da API interna do serviço stock (issue #47, saga stock + wallet): o salto {@code reservation -> stock}. Mesmo desenho do
 * {@code ReservationClient}: Apache HttpClient 5 com pool dimensionável (total e por rota; deve ser >= às threads do Tomcat do
 * chamador, senão a fila se forma aqui), timeouts explícitos (conectar, esperar conexão livre do pool, esperar a resposta),
 * {@code evictIdle} menor que o keep-alive do servidor (60 s) e SEM retry automático: repetir é decisão do chamador, e o
 * {@code holdId} existe para isso (idempotente nos dois passos).
 *
 * <p>É também o {@link EventCapacityApi} (a API {@code /internal/eventcaps/**}, que o catalog usa e o reservation lê para a
 * disponibilidade): os mesmos verbos, caminhos e códigos que o {@code ReservationClient} tinha antes da #47.</p>
 *
 * <p><b>Erros</b> (corpo da resposta de erro = mensagem em texto puro): 400 => {@link IllegalArgumentException} (regra de negócio:
 * "Evento lotado" etc.); 409 => {@link StockConflictException} (em {@code hold}, {@link HoldLiberadoException}: a lápide); 404 =>
 * depende do método; qualquer outro status (inclusive 401 e 5xx), timeout e conexão recusada => {@link RuntimeException}, que o
 * orquestrador trata como falha do passo e compensa.</p>
 */
public class StockClient implements EventCapacityApi {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    /** Header do token de serviço compartilhado que o stock exige em toda requisição. */
    public static final String HEADER_TOKEN_INTERNO = "X-Internal-Token";

    private final RestClient http;
    private final CloseableHttpClient httpClient;

    /** @param internalToken {@code INTERNAL_API_TOKEN}, enviado em TODA chamada; obrigatório (sem ele o stock responde 401 a tudo). */
    public StockClient(String baseUrl, String internalToken, int maxConnections, Duration connectTimeout,
            Duration connectionRequestTimeout, Duration responseTimeout, Duration evictIdle) {
        this(baseUrl, internalToken, maxConnections, connectTimeout, connectionRequestTimeout, responseTimeout, evictIdle,
                ClientLb.desligado());
    }

    /** @param lb balanceamento no cliente (trilha C da #197): soma resolver round-robin, TTL e reuso FIFO ao pool; desligado não muda nada */
    public StockClient(String baseUrl, String internalToken, int maxConnections, Duration connectTimeout,
            Duration connectionRequestTimeout, Duration responseTimeout, Duration evictIdle,
            ClientLb lb) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_TOKEN não configurado: o cliente do stock precisa do token de "
                    + "serviço (o mesmo valor do serviço stock). Gere um com `openssl rand -hex 32` e ponha no Projeto/.env.");
        }
        ConnectionConfig.Builder conexao = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(responseTimeout.toMillis()))
                // socket fechado pelo stock (queda e volta em menos que o evict de 20 s) é descartado na retirada do pool, sem
                // depender do default da lib
                .setValidateAfterInactivity(TimeValue.ofSeconds(2));
        PoolingHttpClientConnectionManagerBuilder construtor = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(maxConnections)
                .setMaxConnPerRoute(maxConnections);
        lb.aplicar(construtor, conexao);
        PoolingHttpClientConnectionManager pool = construtor.setDefaultConnectionConfig(conexao.build()).build();
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
                .defaultHeader(HEADER_TOKEN_INTERNO, internalToken)
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

    /**
     * Reserva a vaga do {@code holdId}; repetir o mesmo id devolve o mesmo hold sem novo débito.
     * @throws IllegalArgumentException 400 do stock ("Capacidade do evento não configurada", "Janela de compra encerrada para este
     *         evento", "Evento lotado")
     * @throws HoldLiberadoException 409: o hold já foi liberado (lápide)
     */
    public HoldResponse hold(HoldRequest pedido) {
        try {
            return http.post().uri("/internal/holds").contentType(JSON).body(pedido).retrieve().body(HoldResponse.class);
        } catch (StockConflictException ex) {
            throw new HoldLiberadoException(ex.getMessage());
        }
    }

    /** Libera o hold (idempotente: devolve a vaga no máximo uma vez; de um hold que não existia, grava a lápide). */
    public void release(UUID holdId, Long eventId, Long userId) {
        http.post().uri("/internal/holds/{holdId}/release", holdId).contentType(JSON)
                .body(new ReleaseRequest(eventId, userId)).retrieve().toBodilessEntity();
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
        // limite de header do Tomcat do stock
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

    private static RuntimeException erro(int status, String mensagem) {
        return switch (status) {
            case 400 -> new IllegalArgumentException(mensagem);
            case 404 -> new NaoEncontrado(mensagem);
            case 409 -> new StockConflictException(mensagem);
            default -> new RuntimeException("Serviço stock respondeu HTTP " + status + ": " + mensagem);
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
