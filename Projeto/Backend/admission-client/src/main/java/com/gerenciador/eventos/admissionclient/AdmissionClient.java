package com.gerenciador.eventos.admissionclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.gerenciador.eventos.clientlb.ClientLb;
import com.gerenciador.eventos.admissionclient.api.AdmissionApi;
import com.gerenciador.eventos.admissionclient.api.AdmissionRecusadaException;
import com.gerenciador.eventos.admissionclient.api.AdmissionRequest;
import com.gerenciador.eventos.admissionclient.api.AdmissionResult;
import com.gerenciador.eventos.admissionclient.api.AdmissionStatus;
import com.gerenciador.eventos.admissionclient.api.AdmissionUnavailableException;

/**
 * Cliente HTTP da API interna do serviço admission (issue #41, Fase 7): o salto {@code gateway -> admission} que conta cada tentativa
 * de reserva no contador do evento. É o único salto novo do caminho síncrono, medido no dry-run da #41.
 *
 * <p><b>Fail-open.</b> O admission é só sinal de carga: qualquer falha dele (rede, timeout, 5xx, 401) devolve
 * {@link AdmissionResult#sync(long) sync} e a reserva segue pelo caminho de hoje. O 400 (pedido que ele recusa, ex.: chave com mais de 255
 * caracteres) também NÃO falha a reserva na #41: o serviço está vivo (não conta para o disjuntor), a tentativa só não é contada, e o
 * reservation dá o 400 de sempre à chave longa do cliente. <b>Disjuntor:</b>
 * {@code falhasParaAbrir} falhas seguidas abrem o circuito por {@code abertoPor}; nesse tempo nenhuma chamada sai (tudo
 * {@code sync}). Passado o tempo, UMA chamada testa o serviço (as outras seguem {@code sync} enquanto ela voa): sucesso fecha; falha
 * reabre pelo tempo todo. Log WARN com limite de taxa (no máximo 1 linha a cada 10 s, com a contagem acumulada); 401 é ERROR.</p>
 *
 * <p><b>Pool e timeouts</b> como o {@code ReservationClient}: {@code maxConnections} total e por rota (>= threads do Tomcat do
 * gateway), keep-alive, {@code evictIdle} abaixo do keep-alive do servidor (60 s), sem retry automático. Os timeouts são curtos
 * (conectar 200 ms, pool 200 ms, resposta 500 ms nos defaults do {@link AdmissionClientConfig}): o fail-open não pode custar mais
 * que o benefício.</p>
 */
public class AdmissionClient implements AdmissionApi, AutoCloseable {

    /** Header do token de serviço compartilhado que o admission exige em toda requisição (mesmo valor dos outros serviços). */
    public static final String HEADER_TOKEN_INTERNO = "X-Internal-Token";

    private static final Logger log = LoggerFactory.getLogger(AdmissionClient.class);
    private static final long INTERVALO_LOG_NANOS = Duration.ofSeconds(10).toNanos();
    /** Valor de {@code abertoAte} com o circuito fechado. */
    private static final long FECHADO = Long.MIN_VALUE;

    private final RestClient http;
    private final CloseableHttpClient httpClient;
    private final int falhasParaAbrir;
    private final long abertoPorNanos;
    private final LongSupplier relogio;
    private final AtomicInteger falhasSeguidas = new AtomicInteger();
    private final AtomicLong abertoAte = new AtomicLong(FECHADO);
    private final LogComLimite avisos;
    private final LogComLimite erros;

    /**
     * @param internalToken token de serviço ({@code INTERNAL_API_TOKEN}), enviado em TODA chamada; obrigatório: sem ele o admission
     *                      responde 401 a tudo, então falhar aqui, na construção, é melhor que descobrir em produção
     * @param relogio       fonte de tempo em nanos (monotônica); {@code System::nanoTime} em produção, controlável nos testes
     */
    public AdmissionClient(String baseUrl, String internalToken, int maxConnections, Duration connectTimeout,
            Duration connectionRequestTimeout, Duration responseTimeout, Duration evictIdle, int falhasParaAbrir,
            Duration abertoPor, LongSupplier relogio) {
        this(baseUrl, internalToken, maxConnections, connectTimeout, connectionRequestTimeout, responseTimeout, evictIdle,
                falhasParaAbrir, abertoPor, relogio, ClientLb.desligado());
    }

    /** @param lb balanceamento no cliente (trilha C da #197): soma resolver round-robin, TTL e reuso FIFO ao pool; desligado não muda nada */
    public AdmissionClient(String baseUrl, String internalToken, int maxConnections, Duration connectTimeout,
            Duration connectionRequestTimeout, Duration responseTimeout, Duration evictIdle, int falhasParaAbrir,
            Duration abertoPor, LongSupplier relogio,
            ClientLb lb) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_TOKEN não configurado: o cliente do admission precisa do token de "
                    + "serviço (o mesmo valor do serviço admission). Gere um com `openssl rand -hex 32` e ponha no Projeto/.env.");
        }
        this.falhasParaAbrir = falhasParaAbrir;
        this.abertoPorNanos = abertoPor.toNanos();
        this.relogio = relogio;
        this.avisos = LogComLimite.warn(log, INTERVALO_LOG_NANOS, relogio);
        this.erros = LogComLimite.error(log, INTERVALO_LOG_NANOS, relogio);
        ConnectionConfig.Builder conexao = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(responseTimeout.toMillis()));
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
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HEADER_TOKEN_INTERNO, internalToken)
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .defaultStatusHandler(HttpStatusCode::isError, (req, resp) -> {
                    throw erro(resp.getStatusCode().value(),
                            new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8));
                })
                .build();
    }

    /** Fecha o pool (destroyMethod do bean; os testes também). */
    @Override
    public void close() throws IOException {
        httpClient.close();
    }

    @Override
    public AdmissionResult admit(AdmissionRequest request) {
        if (!podeChamar()) {
            return AdmissionResult.sync(-1);
        }
        try {
            AdmissionResult resultado = http.post().uri("/internal/admissions").contentType(MediaType.APPLICATION_JSON)
                    .body(request).retrieve().body(AdmissionResult.class);
            sucesso();
            return resultado != null ? resultado : AdmissionResult.sync(-1);
        } catch (AdmissionRecusadaException ex) {
            sucesso(); // 422/429 (#43): recusa de negócio de um serviço vivo; o gateway a repassa ao cliente, sem fail-open
            throw ex;
        } catch (PedidoRecusado ex) {
            sucesso(); // 400: o serviço está vivo e respondeu; a tentativa só não foi contada
            avisos.registrar("[admission-client] o admission recusou o pedido (HTTP 400: " + ex.getMessage()
                    + "): reserva segue síncrona, sem contar");
        } catch (NaoAutorizado ex) {
            erros.registrar("[admission-client] o admission recusou o token interno (HTTP 401): INTERNAL_API_TOKEN diferente nos "
                    + "dois lados. Reserva segue síncrona (fail-open)");
            falha();
        } catch (RuntimeException ex) {
            avisos.registrar("[admission-client] admission indisponível (" + ex + "): reserva segue síncrona (fail-open)");
            falha();
        }
        return AdmissionResult.sync(-1);
    }

    /**
     * Consulta do estado de uma tentativa da fila (issue #43). Fora do disjuntor: não lê nem altera o circuito do {@link #admit} (uma
     * consulta que falha não desliga a contagem, e o circuito aberto não impede a consulta). Os parâmetros vão codificados pelo
     * {@code RestClient} (variáveis de URI), nunca concatenados.
     */
    @Override
    public Optional<AdmissionStatus> result(long userId, String idempotencyKey) {
        try {
            AdmissionStatus estado = http.get().uri(b -> b.path("/internal/admissions/result").queryParam("userId", "{u}")
                    .queryParam("idempotencyKey", "{k}").build(userId, idempotencyKey)).retrieve().body(AdmissionStatus.class);
            return Optional.ofNullable(estado);
        } catch (NaoEncontrado ex) {
            return Optional.empty();
        } catch (RuntimeException ex) {
            throw new AdmissionUnavailableException("admission indisponível na consulta: " + ex, ex);
        }
    }

    /** {@code true} se a chamada pode sair: circuito fechado, ou aberto com o tempo vencido (esta chamada é a sonda). */
    private boolean podeChamar() {
        long ate = abertoAte.get();
        if (ate == FECHADO) {
            return true;
        }
        long agora = relogio.getAsLong();
        if (agora - ate < 0) {
            return false;
        }
        // Tempo vencido: UM chamador ganha a vez de testar (empurra o prazo; os outros seguem sync até a sonda terminar).
        return abertoAte.compareAndSet(ate, agora + abertoPorNanos);
    }

    private void sucesso() {
        falhasSeguidas.set(0);
        abertoAte.set(FECHADO);
    }

    private void falha() {
        if (falhasSeguidas.incrementAndGet() >= falhasParaAbrir) {
            // contagem fica >= limite: a sonda que falhar reabre na hora
            abertoAte.set(relogio.getAsLong() + abertoPorNanos);
        }
    }

    private static RuntimeException erro(int status, String mensagem) {
        return switch (status) {
            case 400 -> new PedidoRecusado(mensagem);
            case 401 -> new NaoAutorizado();
            case 404 -> new NaoEncontrado();
            case 422, 429 -> new AdmissionRecusadaException(status, mensagem);
            default -> new RuntimeException("HTTP " + status + ": " + mensagem);
        };
    }

    /** 404 de {@code GET .../result}: não há estado para a chave (resposta normal, não falha). */
    private static final class NaoEncontrado extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** 400 do admission: o serviço respondeu e recusou o pedido (não é falha do serviço, e não falha a reserva). */
    private static final class PedidoRecusado extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PedidoRecusado(String mensagem) {
            super(mensagem);
        }
    }

    /** 401 do admission: erro de configuração (token diferente), tratado como falha mas logado em ERROR. */
    private static final class NaoAutorizado extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
