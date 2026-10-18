package com.gerenciador.eventos.reservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Job de retenção da {@code idempotency_key} (issue #130): apaga as chaves com {@code created_at} mais
 * antigo que {@code idempotency.retention.hours} (default 24h, a janela em que um retry do cliente faz
 * sentido), a cada {@code idempotency.retention.scan.ms} (default 10 min). Sem isso a tabela e o índice
 * da PK crescem sem limite sob carga.
 *
 * <p>Apaga em lotes de {@value #LOTE} linhas, cada um numa instrução própria (autocommit), até um lote
 * voltar incompleto — não varre nem trava a tabela inteira de uma vez. Mesmo padrão de
 * {@link ReservationExpiryService}; {@code @EnableScheduling} está em
 * {@code shared/SchedulingConfig}. {@link #purgeExpired()} é {@code public} e chamável direto (os testes
 * o invocam sem esperar o timer).</p>
 */
@Service
public class IdempotencyKeyRetentionService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyRetentionService.class);

    /** Tamanho do lote do DELETE. */
    static final int LOTE = 1000;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Value("${idempotency.retention.hours:24}")
    private int retentionHours;

    @Scheduled(fixedDelayString = "${idempotency.retention.scan.ms:600000}",
               initialDelayString = "${idempotency.retention.scan.ms:600000}")
    public int purgeExpired() {
        int total = 0;
        int apagadas;
        do {
            apagadas = idempotencyKeyRepository.deleteOlderThan(retentionHours, LOTE);
            total += apagadas;
        } while (apagadas == LOTE);
        if (total > 0) {
            log.info("[IdempotencyRetention] {} chave(s) com mais de {}h apagada(s)", total, retentionHours);
        }
        return total;
    }
}
