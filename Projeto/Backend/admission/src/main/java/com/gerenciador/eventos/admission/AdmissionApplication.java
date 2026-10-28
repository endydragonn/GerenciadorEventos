package com.gerenciador.eventos.admission;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Serviço {@code admission} (issue #41, Fase 7): dono do contador de RPM por evento (Redis, janela deslizante por buckets) e da
 * API interna HTTP ({@code /internal/admissions}) que o gateway do monólito chama a cada tentativa de reserva. Processo e imagem
 * próprios, <b>sem datasource</b> (não depende de {@code shared}, que traz o {@code DatabaseConnection}; não toca no Postgres).
 *
 * <p>O component scan cobre só este pacote: a lib {@code admission-client} entra pelos DTOs, e o {@code AdmissionClientConfig}
 * (que montaria um cliente de si mesmo) fica FORA do scan.</p>
 */
@SpringBootApplication
public class AdmissionApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdmissionApplication.class, args);
    }
}
