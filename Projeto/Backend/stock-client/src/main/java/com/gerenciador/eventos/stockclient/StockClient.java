package com.gerenciador.eventos.stockclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.stockclient.api.HoldLiberadoException;
import com.gerenciador.eventos.stockclient.api.HoldRequest;
import com.gerenciador.eventos.stockclient.api.HoldResponse;
import com.gerenciador.eventos.stockclient.api.ReleaseRequest;

/**
 * Cliente HTTP da API interna do serviço stock (issue #47, saga stock + wallet): o salto {@code reservation -> stock}. Mesmo desenho
 * do {@code ReservationClient}: Apache HttpClient 5 com pool dimensionável (total e por rota; deve ser >= às threads do Tomcat do
 * chamador, senão a fila se forma aqui), timeouts explícitos (conectar, esperar conexão livre do pool, esperar a resposta),
 * {@code evictIdle} menor que o keep-alive do servidor (60 s) e SEM retry automático: repetir é decisão do chamador, e o
 * {@code holdId} existe para isso (idempotente nos dois passos).
 *
 * <p><b>Erros</b> (corpo da resposta de erro = mensagem em texto puro): 400 => {@link IllegalArgumentException} (regra de negócio:
 * "Evento lotado" etc.); 409 em {@code hold} => {@link HoldLiberadoException} (lápide); qualquer outro status (inclusive 401 e 5xx),
 * timeout e conexão recusada => {@link RuntimeException}, que o orquestrador trata como falha do passo e compensa.</p>
 */
public class StockClient {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    /** Header do token de serviço compartilhado que o stock exige em toda requisição. */
    public static final String HEADER_TOKEN_INTERNO = "X-Internal-Token";

    private final RestClient http;
    private final CloseableHttpClient httpClient;

    /** @param internalToken {@code INTERNAL_API_TOKEN}, enviado em TODA chamada; obrigatório (sem ele o stock responde 401 a tudo). */
    public StockClient(String baseUrl, String internalToken, int maxConnections, Duration connectTimeout,
            Duration connectionRequestTimeout, Duration responseTimeout, Duration evictIdle) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_TOKEN não configurado: o cliente do stock precisa do token de "
                    + "serviço (o mesmo valor do serviço stock). Gere um com `openssl rand -hex 32` e ponha no Projeto/.env.");
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
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
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
        } catch (Conflito ex) {
            throw new HoldLiberadoException(ex.getMessage());
        }
    }

    /** Libera o hold (idempotente: devolve a vaga no máximo uma vez; de um hold que não existia, grava a lápide). */
    public void release(UUID holdId, Long eventId, Long userId) {
        http.post().uri("/internal/holds/{holdId}/release", holdId).contentType(JSON)
                .body(new ReleaseRequest(eventId, userId)).retrieve().toBodilessEntity();
    }

    private static RuntimeException erro(int status, String mensagem) {
        return switch (status) {
            case 400 -> new IllegalArgumentException(mensagem);
            case 409 -> new Conflito(mensagem);
            default -> new RuntimeException("Serviço stock respondeu HTTP " + status + ": " + mensagem);
        };
    }

    /** 409 do serviço; cada método decide o que ele significa. */
    private static final class Conflito extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Conflito(String mensagem) {
            super(mensagem);
        }
    }
}
