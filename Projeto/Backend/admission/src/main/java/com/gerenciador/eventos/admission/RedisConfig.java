package com.gerenciador.eventos.admission;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;

/**
 * Configuração do Redis do admission (issue #41). Com o Redis fora, o padrão do Lettuce é ENFILEIRAR os comandos enquanto reconecta
 * (cada um esperando o timeout); aqui eles são REJEITADOS na hora ({@code REJECT_COMMANDS}), para o fail-open ser rápido. O
 * {@code ClientOptions} que o Spring Boot monta (com o timeout de conexão) é substituído, então o timeout de conexão volta aqui.
 * Com a conexão estabelecida (a compartilhada e permanente) e o Redis travado, cada comando falha no timeout de 200 ms
 * ({@code AdmissionRedisTravadoTest}); sem conexão, os comandos são rejeitados na hora ({@code AdmissionRedisRecusadoTest}).
 */
@Configuration
public class RedisConfig {

    @Bean
    Clock relogio() {
        return Clock.systemUTC();
    }

    @Bean
    LettuceClientConfigurationBuilderCustomizer rejeitarComandosSemConexao(
            @Value("${spring.data.redis.connect-timeout:200ms}") Duration connectTimeout) {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
                .build());
    }
}
