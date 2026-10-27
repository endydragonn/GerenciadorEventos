package com.gerenciador.eventos.identityclient;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean do {@link IdentityClient} (o {@code UserApi}/{@code EmailVerificationApi}/{@code MyWalletApi}/{@code RecaptchaApi} que o
 * gateway injeta desde o cutover do identity, issue #36). Tudo por env var: {@code IDENTITY_SERVICE_URL}, {@code INTERNAL_API_TOKEN}
 * (obrigatória: o contexto não sobe sem ela), {@code IDENTITY_CLIENT_MAX_CONNECTIONS} e
 * {@code IDENTITY_CLIENT_*_TIMEOUT_MS}/{@code IDENTITY_CLIENT_EVICT_IDLE_MS} (o binding relaxado do Spring mapeia
 * {@code identity.client.max-connections} <- {@code IDENTITY_CLIENT_MAX_CONNECTIONS}). Resposta 5 s, sem retry (spec "Cliente").
 *
 * <p>O serviço identity NÃO escaneia este pacote (seu {@code scanBasePackages} é {@code identity} + {@code shared}): só o gateway
 * (monólito) monta o cliente.</p>
 */
@Configuration
public class IdentityClientConfig {

    @Bean(destroyMethod = "close")
    IdentityClient identityClient(
            @Value("${identity.service.url:http://localhost:8084}") String baseUrl,
            @Value("${internal.api.token:}") String internalToken,
            @Value("${identity.client.max-connections:400}") int maxConnections,
            @Value("${identity.client.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${identity.client.connection-request-timeout-ms:5000}") long connectionRequestTimeoutMs,
            @Value("${identity.client.response-timeout-ms:5000}") long responseTimeoutMs,
            @Value("${identity.client.evict-idle-ms:20000}") long evictIdleMs) {
        return new IdentityClient(baseUrl, internalToken, maxConnections, Duration.ofMillis(connectTimeoutMs),
                Duration.ofMillis(connectionRequestTimeoutMs), Duration.ofMillis(responseTimeoutMs),
                Duration.ofMillis(evictIdleMs));
    }
}
