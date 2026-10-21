package com.gerenciador.eventos.reservationclient;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * Bean do {@link ReservationClient} do monólito (ele é, ao mesmo tempo, o {@code EventWalletApi} e o {@code EventCapacityApi}
 * que o gateway e o catalog injetam). Tudo por env var (nomes no application.properties do monólito):
 * {@code RESERVATION_SERVICE_URL}, {@code RESERVATION_CLIENT_MAX_CONNECTIONS}, {@code RESERVATION_CLIENT_*_TIMEOUT_MS}.
 */
@Configuration
public class ReservationClientConfig {

    @Bean(destroyMethod = "close")
    ReservationClient reservationClient(
            @Value("${reservation.service.url:http://localhost:8082}") String baseUrl,
            @Value("${reservation.client.max-connections:400}") int maxConnections,
            @Value("${reservation.client.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${reservation.client.connection-request-timeout-ms:5000}") long connectionRequestTimeoutMs,
            @Value("${reservation.client.response-timeout-ms:10000}") long responseTimeoutMs,
            @Value("${reservation.client.evict-idle-ms:20000}") long evictIdleMs) {
        return new ReservationClient(baseUrl, maxConnections, Duration.ofMillis(connectTimeoutMs),
                Duration.ofMillis(connectionRequestTimeoutMs), Duration.ofMillis(responseTimeoutMs),
                Duration.ofMillis(evictIdleMs));
    }
}
