package com.gerenciador.eventos.stockclient;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gerenciador.eventos.clientlb.ClientLb;

/**
 * Bean do {@link StockClient} de quem chama o serviço stock: o reservation (orquestra a saga e lê a capacidade) e o catalog (mexe na
 * capacidade; o bean é, ao mesmo tempo, o {@code EventCapacityApi} que ele injeta). Tudo por env var (nomes no application.properties
 * de cada serviço): {@code STOCK_SERVICE_URL}, {@code INTERNAL_API_TOKEN} (obrigatória: o contexto não sobe sem ela),
 * {@code STOCK_CLIENT_MAX_CONNECTIONS} (default 400, o do reservation; o catalog usa 100) e {@code STOCK_CLIENT_*_TIMEOUT_MS}.
 */
@Configuration
public class StockClientConfig {

    @Bean(destroyMethod = "close")
    StockClient stockClient(
            @Value("${stock.service.url:http://localhost:8086}") String baseUrl,
            @Value("${internal.api.token:}") String internalToken,
            @Value("${stock.client.max-connections:400}") int maxConnections,
            @Value("${stock.client.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${stock.client.connection-request-timeout-ms:5000}") long connectionRequestTimeoutMs,
            @Value("${stock.client.response-timeout-ms:10000}") long responseTimeoutMs,
            @Value("${stock.client.evict-idle-ms:20000}") long evictIdleMs,
            @Value("${client.lb.enabled:false}") boolean lbAtivo,
            @Value("${client.lb.ttl-ms:15000}") long lbTtlMs) {
        return new StockClient(baseUrl, internalToken, maxConnections, Duration.ofMillis(connectTimeoutMs),
                Duration.ofMillis(connectionRequestTimeoutMs), Duration.ofMillis(responseTimeoutMs),
                Duration.ofMillis(evictIdleMs), new ClientLb(lbAtivo, lbTtlMs));
    }
}
