package com.gerenciador.eventos.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Serviço {@code catalog} (issue #35, Fase 7: terceira extração): dono dos eventos em CQRS + event sourcing (stream
 * {@code catalog.event}, projeção inline {@code event}/{@code eventpresenter}), tudo no schema {@code catalog}. Expõe a API
 * interna HTTP ({@code /internal/events/**}) que o gateway do monólito chama pelo {@code CatalogClient}.
 *
 * <p>O component scan cobre este pacote, a lib {@code shared} (conexão, event store, relay, replay; o {@code EventStore}
 * aponta para o schema {@code catalog} por {@code events.schema}) e o {@code reservationclient} (o bean do
 * {@code ReservationClient}: o {@code eventcap} nasce/muda/sai por HTTP no serviço reservation). O {@code catalogclient} fica
 * FORA do scan de propósito: o serviço não monta um cliente de si mesmo. {@code @EnableScheduling} liga o relay do outbox.</p>
 */
@SpringBootApplication(scanBasePackages = {"com.gerenciador.eventos.catalog", "com.gerenciador.eventos.shared",
        "com.gerenciador.eventos.reservationclient"})
@EnableScheduling
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
