package com.gerenciador.eventos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import java.io.InputStream;
import java.util.Properties;

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
        "reservation.expiry.scan.ms=3600000"})
class ConnectionPoolSizeTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void tamanhoMaximoDoPool_eConfiguravel() {
        assertEquals(7, assertInstanceOf(HikariDataSource.class, dataSource).getMaximumPoolSize());
    }

    /**
     * Issue #36: o gateway não toca mais em tabela de domínio (usuários, eventos e reservas são de serviços), então o Hikari do monólito
     * caiu de 350 para 10 (só health e testes da infra). A soma no Supavisor: 1000 + 10 + 3 + 100 + 100 = 1213 <= 1500. Lê o DEFAULT do
     * properties (o valor efetivo pode vir de SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE no ambiente).
     */
    @Test
    void defaultDoPoolDoGatewayE10() throws Exception {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            p.load(in);
        }
        assertEquals("${SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE:10}", p.getProperty("spring.datasource.hikari.maximum-pool-size"));
    }
}
