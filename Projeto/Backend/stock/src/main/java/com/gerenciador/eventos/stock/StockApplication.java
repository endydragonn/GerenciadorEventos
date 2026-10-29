package com.gerenciador.eventos.stock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Serviço {@code stock} (issue #47, saga stock + wallet): dono de {@code eventcap} (CAS) e do ledger {@code hold}, no schema
 * {@code stock}. Expõe a API interna HTTP ({@code /internal/**}) que o reservation (holds) e o catalog (capacidade) chamam. Sem event
 * store nem relay: só consome ({@code reservation.wallet} para devolver vaga, {@code catalog.event} para reconciliar o eventcap).
 *
 * <p>O component scan cobre este pacote e a lib {@code shared} (conexão e política de erro dos consumers).</p>
 */
@SpringBootApplication(scanBasePackages = {"com.gerenciador.eventos.stock", "com.gerenciador.eventos.shared"})
public class StockApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockApplication.class, args);
    }
}
