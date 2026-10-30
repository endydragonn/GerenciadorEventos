package com.gerenciador.eventos.catalogclient;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gerenciador.eventos.clientlb.ClientLb;

/**
 * Bean do {@link CatalogClient} (o {@code EventApi} que o gateway injeta quando o catalog sai do processo, issue #35). Tudo por
 * env var: {@code CATALOG_SERVICE_URL}, {@code INTERNAL_API_TOKEN} (obrigatória: o contexto não sobe sem ela),
 * {@code CATALOG_CLIENT_MAX_CONNECTIONS} e {@code CATALOG_CLIENT_*_TIMEOUT_MS}/{@code CATALOG_CLIENT_EVICT_IDLE_MS}
 * (o binding relaxado do Spring mapeia {@code catalog.client.max-connections} <- {@code CATALOG_CLIENT_MAX_CONNECTIONS}).
 * Timeout de resposta 15 s, sem retry (spec "Pool").
 */
@Configuration
public class CatalogClientConfig {

    @Bean(destroyMethod = "close")
    CatalogClient catalogClient(
            @Value("${catalog.service.url:http://localhost:8083}") String baseUrl,
            @Value("${internal.api.token:}") String internalToken,
            @Value("${catalog.client.max-connections:400}") int maxConnections,
            @Value("${catalog.client.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${catalog.client.connection-request-timeout-ms:5000}") long connectionRequestTimeoutMs,
            @Value("${catalog.client.response-timeout-ms:15000}") long responseTimeoutMs,
            @Value("${catalog.client.evict-idle-ms:20000}") long evictIdleMs,
            @Value("${client.lb.enabled:false}") boolean lbAtivo,
            @Value("${client.lb.ttl-ms:15000}") long lbTtlMs) {
        return new CatalogClient(baseUrl, internalToken, maxConnections, Duration.ofMillis(connectTimeoutMs),
                Duration.ofMillis(connectionRequestTimeoutMs), Duration.ofMillis(responseTimeoutMs),
                Duration.ofMillis(evictIdleMs), new ClientLb(lbAtivo, lbTtlMs));
    }
}
