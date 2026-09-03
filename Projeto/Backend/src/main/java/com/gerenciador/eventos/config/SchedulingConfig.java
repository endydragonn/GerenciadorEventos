package com.gerenciador.eventos.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita os jobs agendados (@Scheduled), como
 * {@link com.gerenciador.eventos.Service.ReservationExpiryService}.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
