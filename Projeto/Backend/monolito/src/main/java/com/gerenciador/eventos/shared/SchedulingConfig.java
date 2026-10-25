package com.gerenciador.eventos.shared;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita os jobs agendados (@Scheduled) do monólito: o relay do outbox (identity; o catalog saiu na #35) e o
 * {@code NotificationEventRetentionService}. A expiração de reservas e a retenção de chaves de idempotência são do
 * serviço reservation (que tem a sua própria habilitação).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
