package com.gerenciador.eventos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * Issue #96: o tamanho máximo do pool vem de {@code spring.datasource.hikari.maximum-pool-size}
 * (em produção, da env {@code SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE}). Contexto próprio
 * (pool de 7) para provar que é configurável sem fixar o default do properties.
 */
@SpringBootTest(properties = {"spring.datasource.hikari.maximum-pool-size=7",
        "readmodel.availability.refresh.ms=3600000", "reservation.expiry.scan.ms=3600000"})
class ConnectionPoolSizeTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void tamanhoMaximoDoPool_eConfiguravel() {
        assertEquals(7, assertInstanceOf(HikariDataSource.class, dataSource).getMaximumPoolSize());
    }
}
