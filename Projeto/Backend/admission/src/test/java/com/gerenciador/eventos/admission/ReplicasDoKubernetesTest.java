package com.gerenciador.eventos.admission;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * A leitura das réplicas ativas do reservation pela API do Kubernetes (issue #197, D4) contra um servidor HTTP FALSO no lugar da API (JDK
 * {@code HttpServer}, texto puro; o TLS com a CA própria do cluster é coberto pelo e2e, que sobe o reservation para 2 e confere {@code adm:replicas = 2}):
 * {@code status.readyReplicas} dentro de {@code [1, K8S_RESERVATION_MAX]} vira a chave {@code adm:replicas} com TTL de 15 s; 403, timeout, JSON inválido,
 * {@code readyReplicas} ausente, 0 ou acima do máximo NÃO gravam (a chave expira e vale {@code ADMISSION_RESERVATION_REPLICAS}, o lado seguro). O token
 * da ServiceAccount é relido a cada chamada (o projetado gira). Redis simulado: o teste não precisa de Redis.
 */
class ReplicasDoKubernetesTest {

    @TempDir Path pasta;
    private HttpServer servidor;
    private Path token;
    private Path ns;
    private volatile int status = 200;
    private volatile String corpo = "{\"status\":{\"readyReplicas\":2}}";
    private volatile long atrasoMs = 0;
    private final List<String> autorizacoes = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> caminho = new AtomicReference<>();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valores;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void montar() throws IOException {
        token = pasta.resolve("token");
        ns = pasta.resolve("namespace");
        Files.writeString(token, "token-1\n");
        Files.writeString(ns, "gerenciador");
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", ex -> {
            autorizacoes.add(ex.getRequestHeaders().getFirst("Authorization"));
            caminho.set(ex.getRequestURI().getPath());
            try {
                Thread.sleep(atrasoMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        servidor.start();
        redis = mock(StringRedisTemplate.class);
        valores = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valores);
    }

    @AfterEach
    void parar() {
        servidor.stop(0);
    }

    private ReplicasDoKubernetes job(int maximo, long timeoutMs) {
        return new ReplicasDoKubernetes(redis, new ObjectMapper(), "http://127.0.0.1:" + servidor.getAddress().getPort(),
                token.toString(), pasta.resolve("ca.crt").toString(), ns.toString(), maximo, timeoutMs);
    }

    @Test
    void readyReplicasValidoViraAChaveComTtlDe15sEUsaOCaminhoEOTokenCertos() {
        ReplicasDoKubernetes j = job(3, 1000);

        assertEquals(OptionalInt.of(2), j.consultar());
        j.atualizar();

        verify(valores).set("adm:replicas", "2", Duration.ofSeconds(15));
        assertEquals("/apis/apps/v1/namespaces/gerenciador/deployments/reservation", caminho.get());
        assertEquals("Bearer token-1", autorizacoes.get(0), "o token vai no Authorization, sem a quebra de linha do arquivo");
    }

    @Test
    void oTokenEReLidoACadaChamada() throws IOException {
        ReplicasDoKubernetes j = job(3, 1000);

        j.consultar();
        Files.writeString(token, "token-2");
        j.consultar();

        assertEquals(List.of("Bearer token-1", "Bearer token-2"), autorizacoes);
    }

    @Test
    void limitesDoIntervaloValido() {
        corpo = "{\"status\":{\"readyReplicas\":1}}";
        assertEquals(OptionalInt.of(1), job(3, 1000).consultar());
        corpo = "{\"status\":{\"readyReplicas\":3}}";
        assertEquals(OptionalInt.of(3), job(3, 1000).consultar());
    }

    @Test
    void foraDoIntervaloOuAusenteNaoGrava() {
        for (String c : new String[] {"{\"status\":{\"readyReplicas\":0}}", "{\"status\":{\"readyReplicas\":4}}", "{\"status\":{\"readyReplicas\":-1}}",
                "{\"status\":{}}", "{\"status\":{\"readyReplicas\":\"2\"}}", "{\"status\":{\"readyReplicas\":2.5}}", "{}"}) {
            corpo = c;
            ReplicasDoKubernetes j = job(3, 1000);
            assertEquals(OptionalInt.empty(), j.consultar(), c);
            j.atualizar();
        }
        verify(valores, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void erroDaApiNaoGrava() {
        for (int codigo : new int[] {401, 403, 404, 500, 503}) {
            status = codigo;
            corpo = "{\"status\":{\"readyReplicas\":2}}";
            ReplicasDoKubernetes j = job(3, 1000);
            assertEquals(OptionalInt.empty(), j.consultar(), "status " + codigo);
            j.atualizar();
        }
        verify(valores, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void jsonInvalidoNaoGrava() {
        corpo = "isto nao e json";
        ReplicasDoKubernetes j = job(3, 1000);

        assertEquals(OptionalInt.empty(), j.consultar());
        j.atualizar();
        verify(valores, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void timeoutNaoGravaEORespeitaOLimite() {
        atrasoMs = 1500;
        ReplicasDoKubernetes j = job(3, 300);

        long inicio = System.nanoTime();
        assertEquals(OptionalInt.empty(), j.consultar());
        long ms = Duration.ofNanos(System.nanoTime() - inicio).toMillis();
        assertTrue(ms < 1200, "o timeout de 300 ms corta a chamada (levou " + ms + " ms)");
        j.atualizar();
        verify(valores, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void servidorForaOuArquivosAusentesNaoGravamENaoLancam() throws IOException {
        servidor.stop(0);
        ReplicasDoKubernetes fora = job(3, 300);
        assertEquals(OptionalInt.empty(), fora.consultar());
        fora.atualizar();

        Files.delete(token);
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.start();
        ReplicasDoKubernetes semToken = job(3, 300);
        assertEquals(OptionalInt.empty(), semToken.consultar());
        semToken.atualizar();

        verify(valores, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void falhaDoRedisNaoPropagaDoJob() {
        when(redis.opsForValue()).thenThrow(new org.springframework.dao.QueryTimeoutException("redis fora"));
        job(3, 1000).atualizar();
    }
}
