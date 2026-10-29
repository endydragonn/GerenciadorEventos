package com.gerenciador.eventos.reservationclient;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * Bean do {@link ReservationClient} do monólito (o {@code EventWalletApi} que o gateway injeta; a capacidade é do serviço stock desde a #47). Tudo por env var (nomes no application.properties do monólito):
 * {@code RESERVATION_SERVICE_URL}, {@code INTERNAL_API_TOKEN} (obrigatória: o contexto não sobe sem ela),
 * {@code RESERVATION_CLIENT_MAX_CONNECTIONS}, {@code RESERVATION_CLIENT_*_TIMEOUT_MS}.
 */
@Configuration
public class ReservationClientConfig {

    @Bean(destroyMethod = "close")
    ReservationClient reservationClient(
            @Value("${reservation.service.url:http://localhost:8082}") String baseUrl,
            @Value("${internal.api.token:}") String internalToken,
            @Value("${reservation.client.max-connections:400}") int maxConnections,
            @Value("${reservation.client.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${reservation.client.connection-request-timeout-ms:5000}") long connectionRequestTimeoutMs,
            @Value("${reservation.client.response-timeout-ms:10000}") long responseTimeoutMs,
            @Value("${reservation.client.evict-idle-ms:20000}") long evictIdleMs) {
        return new ReservationClient(baseUrl, internalToken, maxConnections, Duration.ofMillis(connectTimeoutMs),
                Duration.ofMillis(connectionRequestTimeoutMs), Duration.ofMillis(responseTimeoutMs),
                Duration.ofMillis(evictIdleMs));
    }
}
