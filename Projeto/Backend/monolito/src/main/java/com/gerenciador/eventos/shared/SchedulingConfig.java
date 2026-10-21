package com.gerenciador.eventos.shared;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita os jobs agendados (@Scheduled), como
 * {@code ReservationExpiryService} (módulo reservation).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
