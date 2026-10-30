package com.gerenciador.eventos.admissionclient;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gerenciador.eventos.clientlb.ClientLb;

/**
 * Bean do {@link AdmissionClient} do monólito (o {@code AdmissionApi} que o gateway injeta). Tudo por env var (nomes no
 * application.properties do monólito): {@code ADMISSION_SERVICE_URL}, {@code INTERNAL_API_TOKEN} (obrigatória: o contexto não sobe
 * sem ela), {@code ADMISSION_CLIENT_MAX_CONNECTIONS}, {@code ADMISSION_CLIENT_*_TIMEOUT_MS} e {@code ADMISSION_CLIENT_OPEN_MS}
 * (tempo do circuito aberto depois de 5 falhas seguidas). O serviço admission NÃO escaneia este pacote (sem cliente de si mesmo).
 */
@Configuration
public class AdmissionClientConfig {

    /** Falhas seguidas que abrem o circuito (fixo, spec da #41). */
    private static final int FALHAS_PARA_ABRIR = 5;

    @Bean(destroyMethod = "close")
    AdmissionClient admissionClient(
            @Value("${admission.service.url:http://localhost:8085}") String baseUrl,
            @Value("${internal.api.token:}") String internalToken,
            @Value("${admission.client.max-connections:400}") int maxConnections,
            @Value("${admission.client.connect-timeout-ms:200}") long connectTimeoutMs,
            @Value("${admission.client.connection-request-timeout-ms:200}") long connectionRequestTimeoutMs,
            @Value("${admission.client.response-timeout-ms:500}") long responseTimeoutMs,
            @Value("${admission.client.evict-idle-ms:20000}") long evictIdleMs,
            @Value("${admission.client.open-ms:5000}") long openMs,
            @Value("${client.lb.enabled:false}") boolean lbAtivo,
            @Value("${client.lb.ttl-ms:15000}") long lbTtlMs) {
        return new AdmissionClient(baseUrl, internalToken, maxConnections, Duration.ofMillis(connectTimeoutMs),
                Duration.ofMillis(connectionRequestTimeoutMs), Duration.ofMillis(responseTimeoutMs),
                Duration.ofMillis(evictIdleMs), FALHAS_PARA_ABRIR, Duration.ofMillis(openMs), System::nanoTime,
                new ClientLb(lbAtivo, lbTtlMs));
    }
}
