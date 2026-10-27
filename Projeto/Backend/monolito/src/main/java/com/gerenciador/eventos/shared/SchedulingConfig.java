package com.gerenciador.eventos.shared;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita os jobs agendados (@Scheduled) do monólito: o relay do outbox (hoje só exercitado pelos testes da infra
 * {@code shared.events}; o identity saiu na #36, com o relay e a retenção do dado pessoal dele). A expiração de reservas e a
 * retenção de chaves de idempotência são do serviço reservation (que tem a sua própria habilitação).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
