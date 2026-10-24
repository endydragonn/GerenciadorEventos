package com.gerenciador.eventos.catalogclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

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
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gerenciador.eventos.catalogclient.api.CatalogUnavailableException;
import com.gerenciador.eventos.catalogclient.api.Event;
import com.gerenciador.eventos.catalogclient.api.EventApi;
import com.gerenciador.eventos.catalogclient.api.EventoNaoEncontradoException;

/**
 * Cliente HTTP da API interna do serviço catalog (issue #35): o salto {@code gateway -> catalog}. Cada método de
 * {@link EventApi} é UMA chamada síncrona a {@code /internal/events/**}; nada é cacheado (o cache positivo de {@code exists} é
 * do gateway) nem repetido.
 *
 * <p><b>Pool e timeouts</b> como o {@code ReservationClient} (Apache HttpClient 5, keep-alive, {@code maxConnections} no total e
 * por rota, >= às threads do Tomcat do gateway), com timeout de resposta de 15 s (spec "Pool"); conectar 2 s, esperar conexão
 * livre do pool 5 s, {@code evictIdle} 20 s (< keep-alive de 60 s do catalog). <b>Sem retry automático</b>: um POST repetido às
 * cegas poderia criar o evento duas vezes.</p>
 *
 * <p><b>Erros</b> (o corpo da resposta de erro é a mensagem, texto puro): 400 => {@link IllegalArgumentException}, 404 =>
 * {@link EventoNaoEncontradoException} (ou {@code null}, onde o contrato devolve {@code null}), 409 =>
 * {@link IllegalStateException}; rede, timeout, 5xx e qualquer outro status => {@link CatalogUnavailableException}.
 * {@link #exists} nunca devolve {@code false} por falha.</p>
 */
public class CatalogClient implements EventApi {

    /** Header do token de serviço compartilhado que o catalog exige em toda requisição. */
    public static final String HEADER_TOKEN_INTERNO = "X-Internal-Token";

    private static final String BASE = "/internal/events";

    private final RestClient http;
    private final CloseableHttpClient httpClient;

    /** @param internalToken {@code INTERNAL_API_TOKEN}, enviado em TODA chamada; obrigatório (o catalog responde 401 sem ele) */
    public CatalogClient(String baseUrl, String internalToken, int maxConnections, Duration connectTimeout,
            Duration connectionRequestTimeout, Duration responseTimeout, Duration evictIdle) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_TOKEN não configurado: o cliente do catalog precisa do token de "
                    + "serviço (o mesmo valor do serviço catalog). Gere um com `openssl rand -hex 32` e ponha no Projeto/.env.");
        }
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

    @Override
    public Event createEvent(Event e) {
        return chamar(() -> http.post().uri(BASE).contentType(MediaType.APPLICATION_JSON).body(e).retrieve().body(Event.class));
    }

    @Override
    public Event updateEvent(Event e) {
        return chamar(() -> http.put().uri(BASE + "/{id}", e.getEvent_id()).contentType(MediaType.APPLICATION_JSON).body(e)
                .retrieve().body(Event.class));
    }

    @Override
    public Event findById(Long id) {
        return chamar(() -> http.get().uri(BASE + "/{id}", id).retrieve().body(Event.class));
    }

    @Override
    public void delete(Long id) {
        chamar(() -> http.delete().uri(BASE + "/{id}", id).retrieve().toBodilessEntity());
    }

    @Override
    public String getValidationErrors(Event e) {
        String erros = chamar(() -> http.post().uri(BASE + "/validate").contentType(MediaType.APPLICATION_JSON).body(e)
                .retrieve().body(String.class));
        return erros == null ? "" : erros;
    }

    @Override
    public List<Event> searchEvents(String term) {
        // o termo entra como VALOR de variável ({term}), não no template: assim é codificado por inteiro ("+" vira %2B, não
        // espaço; "{x}" não é variável; "&" não abre outro parâmetro). Sem termo, a query fica sem o parâmetro (= todos).
        return lista(chamar(() -> http.get().uri(b -> {
            b.path(BASE + "/search");
            if (term == null) {
                return b.build();
            }
            return b.queryParam("term", "{term}").build(term);
        }).retrieve().body(new ParameterizedTypeReference<List<Event>>() { })));
    }

    @Override
    public List<Event> findByCreatorId(Long creatorId) {
        if (creatorId == null) {
            return new ArrayList<>();
        }
        return lista(chamar(() -> http.get().uri(b -> b.path(BASE).queryParam("creatorId", creatorId).build())
                .retrieve().body(new ParameterizedTypeReference<List<Event>>() { })));
    }

    /** {@code GET /{id}/exists} (SELECT 1 no serviço). Falha => {@link CatalogUnavailableException}, NUNCA {@code false}. */
    @Override
    public boolean exists(Long eventId) {
        Boolean existe;
        try {
            existe = chamar(() -> http.get().uri(BASE + "/{id}/exists", eventId).retrieve().body(Boolean.class));
        } catch (EventoNaoEncontradoException ex) { // a rota responde 200 true/false: um 404 aqui é falha, não "não existe"
            throw new CatalogUnavailableException("Serviço catalog respondeu 404 em exists: " + ex.getMessage(), ex);
        }
        if (existe == null) {
            throw new CatalogUnavailableException("Serviço catalog respondeu exists sem corpo", null);
        }
        return existe;
    }

    @Override
    public byte[] findImage(Long id) {
        try {
            return chamar(() -> http.get().uri(BASE + "/{id}/image", id).retrieve().body(byte[].class));
        } catch (EventoNaoEncontradoException ex) {
            return null;
        }
    }

    /**
     * Grava a imagem (bytes no corpo). {@code null} ou vazio NÃO apaga a imagem: o corpo vazio é recusado pelo serviço com 400
     * ({@link IllegalArgumentException}); o gateway só chama com a imagem já convertida.
     */
    @Override
    public void saveImage(Long id, byte[] imageData) {
        chamar(() -> http.post().uri(BASE + "/{id}/image", id).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(imageData == null ? new byte[0] : imageData).retrieve().toBodilessEntity());
    }

    @Override
    public Long findCreatorId(Long id) {
        try {
            return chamar(() -> http.get().uri(BASE + "/{id}/creator", id).retrieve().body(Long.class));
        } catch (EventoNaoEncontradoException ex) {
            return null;
        }
    }

    // ===================== erros =====================

    /** Falha de rede, timeout ou resposta ilegível (o RestClient as lança como {@link RestClientException}) => indisponível. */
    private static <T> T chamar(Supplier<T> chamada) {
        try {
            return chamada.get();
        } catch (RestClientException ex) {
            throw new CatalogUnavailableException("Serviço catalog indisponível: " + ex.getMessage(), ex);
        }
    }

    private static List<Event> lista(List<Event> eventos) {
        return eventos == null ? new ArrayList<>() : eventos;
    }

    private static RuntimeException erro(int status, String mensagem) {
        return switch (status) {
            case 400 -> new IllegalArgumentException(mensagem);
            case 404 -> new EventoNaoEncontradoException(mensagem);
            case 409 -> new IllegalStateException(mensagem);
            default -> new CatalogUnavailableException("Serviço catalog respondeu HTTP " + status + ": " + mensagem, null);
        };
    }
}
