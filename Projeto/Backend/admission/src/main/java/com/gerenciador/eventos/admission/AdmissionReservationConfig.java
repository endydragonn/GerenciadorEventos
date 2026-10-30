package com.gerenciador.eventos.admission;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gerenciador.eventos.clientlb.ClientLb;
import com.gerenciador.eventos.reservationclient.ReservationClient;

/**
 * O {@link ReservationClient} do worker pool (issue #42). A lib {@code reservation-client} NÃO é escaneada (o {@code ReservationClientConfig}
 * dela mora fora do pacote deste serviço): o bean é montado aqui. Pool = concorrência do worker + a da dead-letter (cada thread usa no
 * máximo uma conexão), então o pool nunca é o gargalo e o teto de CAS simultâneos é só a concorrência do worker. Timeouts do gateway
 * (conectar 2 s, pool 5 s, resposta 10 s; a carência de 60 s do {@code deadline} fica acima), evict 20 s (abaixo do keep-alive de 60 s do
 * reservation), sem retry: a repetição é da mensagem (nack com requeue e a Idempotency-Key). Token e URL por env
 * ({@code INTERNAL_API_TOKEN}, {@code RESERVATION_SERVICE_URL}); balanceamento no cliente da trilha C do Kubernetes (#197) por
 * {@code CLIENT_LB_ENABLED}/{@code CLIENT_LB_TTL_MS} (desligado por padrão: a montagem é a de sempre).
 */
@Configuration
@ConditionalOnProperty(name = "admission.worker.enabled", havingValue = "true", matchIfMissing = true)
public class AdmissionReservationConfig {

    @Bean(destroyMethod = "close")
    ReservationClient reservationClient(@Value("${reservation.service.url:http://localhost:8082}") String baseUrl,
            @Value("${internal.api.token:}") String internalToken, @Value("${admission.worker.concurrency:32}") int workers,
            @Value("${admission.dlx.concurrency:4}") int deadLetter, @Value("${client.lb.enabled:false}") boolean lbAtivo,
            @Value("${client.lb.ttl-ms:15000}") long lbTtlMs) {
        return new ReservationClient(baseUrl, internalToken, workers + deadLetter, Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofSeconds(10), Duration.ofSeconds(20), new ClientLb(lbAtivo, lbTtlMs));
    }
}
