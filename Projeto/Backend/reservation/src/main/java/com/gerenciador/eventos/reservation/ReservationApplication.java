package com.gerenciador.eventos.reservation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Serviço {@code reservation} (issue #33, Fase 7): dono de {@code eventcap} (CAS), {@code walletevent} (event sourcing,
 * stream {@code reservation.wallet}), {@code idempotency_key} e da projeção {@code event_availability}, tudo no schema
 * {@code reservation}. Expõe a API interna HTTP ({@code /internal/**}) que o gateway do monólito chama. Processo e imagem
 * próprios.
 *
 * <p>O component scan cobre este pacote e a lib {@code shared} (conexão, event store, relay, consumer idempotente,
 * replay): o {@code EventStore} aponta para o schema {@code reservation} por {@code events.schema}, então o log, o outbox
 * e o checkpoint são DESTE serviço e ele não lê tabela de outro. {@code @EnableScheduling} liga o relay, a expiração de
 * reservas e a retenção das chaves de idempotência.</p>
 */
@SpringBootApplication(scanBasePackages = {"com.gerenciador.eventos.reservation", "com.gerenciador.eventos.shared"})
@EnableScheduling
public class ReservationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReservationApplication.class, args);
    }
}
