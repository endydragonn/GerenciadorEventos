package com.gerenciador.eventos.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.gerenciador.eventos.Repository.EventAvailabilityRepository;

/**
 * Job que recalcula o read model desnormalizado e defasado {@code event_availability} num
 * timer. O intervalo ({@code readmodel.availability.refresh.ms}, default 5000) é o único
 * knob de "lag": intervalo maior ⇒ read model mais defasado ⇒ mais overselling no caminho
 * ingênuo de reserva ({@code EventWalletService}, feature flag
 * {@code reservation.readpath=naive}). É o braço de controle do experimento de concorrência
 * da Fase 2 — {@code event_availability} NÃO é fonte de verdade ({@code eventcap} + CAS são).
 *
 * <p>Mesmo padrão de {@link ReservationExpiryService}: {@code @EnableScheduling} está em
 * {@code config/SchedulingConfig}. O {@link #refresh()} é {@code public} e chamável direto
 * (os testes o invocam sem esperar o timer).</p>
 */
@Service
public class ReadModelRefreshService {

    private static final Logger log = LoggerFactory.getLogger(ReadModelRefreshService.class);

    @Autowired
    private EventAvailabilityRepository eventAvailabilityRepository;

    @Scheduled(fixedDelayString = "${readmodel.availability.refresh.ms:5000}",
               initialDelayString = "${readmodel.availability.refresh.ms:5000}")
    public void refresh() {
        int linhas = eventAvailabilityRepository.refreshAll();
        if (linhas > 0) {
            log.info("[ReadModelRefresh] {} linha(s) de event_availability recalculada(s)", linhas);
        }
    }
}
