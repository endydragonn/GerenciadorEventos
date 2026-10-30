package com.gerenciador.eventos.admission;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.OptionalInt;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.admissionclient.LogComLimite;

/**
 * Réplicas ATIVAS do {@code reservation} lidas da API do Kubernetes (issue #197, D4 = b), no lugar do scaler da opção A que gravava {@code adm:replicas}:
 * a cada 2 s faz {@code GET /apis/apps/v1/namespaces/<ns>/deployments/reservation} e grava {@code status.readyReplicas} em {@code adm:replicas} com TTL de
 * 15 s (a mesma chave e o mesmo TTL do scaler; o script Lua do contador não mudou). Ligado SÓ por {@code admission.k8s.replicas.enabled} (desligado no
 * compose: sem a propriedade o bean nem existe e nenhum scheduler sobe). Só o {@code admission} tem token de ServiceAccount, com RBAC de {@code get} no
 * Deployment {@code reservation} e nada mais.
 *
 * <p>Falha NÃO grava: API fora, 401/403, timeout (1 s), JSON inválido e {@code readyReplicas} ausente, 0 ou fora de {@code [1, K8S_RESERVATION_MAX]}
 * deixam a chave expirar e vale {@code ADMISSION_RESERVATION_REPLICAS} (o lado seguro: a fila liga mais cedo). Várias réplicas do admission gravam o
 * mesmo valor (idempotente). O token projetado GIRA, então é relido a cada chamada; a CA do cluster ({@code ca.crt}) vai num {@link SSLContext} próprio
 * (só para {@code https}); o namespace vem do arquivo {@code namespace}. Sem dependência nova: {@link HttpClient} do JDK e o Jackson que já existe.
 * O log de falha tem limite de frequência.</p>
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "admission.k8s.replicas.enabled", havingValue = "true")
public class ReplicasDoKubernetes {

    private static final Logger log = LoggerFactory.getLogger(ReplicasDoKubernetes.class);
    static final Duration TTL = Duration.ofSeconds(15);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String apiUrl;
    private final Path arquivoToken;
    private final Path arquivoCa;
    private final Path arquivoNamespace;
    private final int maximo;
    private final Duration limite;
    private final LogComLimite aviso = LogComLimite.warn(log, Duration.ofSeconds(30).toNanos(), System::nanoTime);
    private volatile HttpClient cliente;

    public ReplicasDoKubernetes(StringRedisTemplate redis, ObjectMapper json,
            @Value("${admission.k8s.api-url:https://kubernetes.default.svc}") String apiUrl,
            @Value("${admission.k8s.token-file:/var/run/secrets/kubernetes.io/serviceaccount/token}") String arquivoToken,
            @Value("${admission.k8s.ca-file:/var/run/secrets/kubernetes.io/serviceaccount/ca.crt}") String arquivoCa,
            @Value("${admission.k8s.namespace-file:/var/run/secrets/kubernetes.io/serviceaccount/namespace}") String arquivoNamespace,
            @Value("${admission.k8s.replicas.max:3}") int maximo,
            @Value("${admission.k8s.timeout-ms:1000}") long timeoutMs) {
        if (maximo < 1 || maximo > 1000) {
            throw new IllegalArgumentException("admission.k8s.replicas.max (" + maximo + ") deve ficar entre 1 e 1000 (o limite do script do contador)");
        }
        this.redis = redis;
        this.json = json;
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.arquivoToken = Path.of(arquivoToken);
        this.arquivoCa = Path.of(arquivoCa);
        this.arquivoNamespace = Path.of(arquivoNamespace);
        this.maximo = maximo;
        this.limite = Duration.ofMillis(timeoutMs);
    }

    @Scheduled(fixedDelayString = "${admission.k8s.replicas.interval-ms:2000}")
    void atualizar() {
        try {
            OptionalInt prontas = consultar();
            if (prontas.isPresent()) {
                redis.opsForValue().set(AdmissionStore.CHAVE_REPLICAS, String.valueOf(prontas.getAsInt()), TTL);
            }
        } catch (RuntimeException ex) { // Redis fora ou lento: a chave expira, vale o env; o job segue no próximo tick
            aviso.registrar("[admission] não gravei adm:replicas (" + ex + ")");
        }
    }

    /** {@code readyReplicas} do Deployment {@code reservation}, ou vazio em qualquer falha (já registrada) ou valor fora de {@code [1, máximo]}. */
    OptionalInt consultar() {
        try {
            String namespace = Files.readString(arquivoNamespace).trim();
            String token = Files.readString(arquivoToken).trim(); // relido a cada chamada: o token projetado gira
            HttpRequest pedido = HttpRequest.newBuilder(URI.create(apiUrl + "/apis/apps/v1/namespaces/" + namespace + "/deployments/reservation"))
                    .timeout(limite).header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build();
            HttpResponse<byte[]> resposta = cliente().send(pedido, HttpResponse.BodyHandlers.ofByteArray());
            if (resposta.statusCode() != 200) {
                aviso.registrar("[admission] API do Kubernetes respondeu " + resposta.statusCode() + " ao ler o Deployment reservation: não gravo adm:replicas");
                return OptionalInt.empty();
            }
            JsonNode prontas = json.readTree(resposta.body()).path("status").path("readyReplicas");
            if (!prontas.isInt() || prontas.intValue() < 1 || prontas.intValue() > maximo) {
                aviso.registrar("[admission] readyReplicas do reservation ausente ou fora de [1, " + maximo + "] (" + prontas + "): não gravo adm:replicas");
                return OptionalInt.empty();
            }
            return OptionalInt.of(prontas.intValue());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return OptionalInt.empty();
        } catch (IOException | GeneralSecurityException | RuntimeException ex) {
            aviso.registrar("[admission] não li as réplicas do reservation na API do Kubernetes (" + ex + "): não gravo adm:replicas");
            return OptionalInt.empty();
        }
    }

    private HttpClient cliente() throws IOException, GeneralSecurityException {
        HttpClient c = cliente;
        if (c == null) {
            HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(limite);
            if (apiUrl.startsWith("https://")) {
                b.sslContext(contextoComACaDoCluster());
            }
            c = cliente = b.build(); // só guarda depois de montar: falha ao ler a CA tenta de novo no tick seguinte
        }
        return c;
    }

    private SSLContext contextoComACaDoCluster() throws IOException, GeneralSecurityException {
        KeyStore confianca = KeyStore.getInstance(KeyStore.getDefaultType());
        confianca.load(null, null);
        try (InputStream in = Files.newInputStream(arquivoCa)) {
            int i = 0;
            for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                confianca.setCertificateEntry("ca-" + i++, cert);
            }
        }
        TrustManagerFactory fabrica = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        fabrica.init(confianca);
        SSLContext contexto = SSLContext.getInstance("TLS");
        contexto.init(null, fabrica.getTrustManagers(), null);
        return contexto;
    }
}
