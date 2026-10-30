package com.gerenciador.eventos.stock;

import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;

/**
 * Trava a configuração do caminho quente do serviço stock (issue #47), a mesma do reservation: Tomcat 300, keep-alive sem teto, virtual
 * threads desligadas, Hikari 250 por padrão (o ~245 do pool real do Supavisor; a conta está no Backend/CLAUDE.md) e porta 8086.
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
public class StockConfigTest {

    @Autowired private ServerProperties serverProperties;
    @Autowired private Environment env;

    @Test
    void tomcatThreadsMaxEhTrezentos() {
        assertEquals(300, serverProperties.getTomcat().getThreads().getMax());
    }

    @Test
    void keepAliveNaoFechaAConexaoACadaCemRequisicoes() {
        assertEquals(-1, serverProperties.getTomcat().getMaxKeepAliveRequests());
        assertEquals(Duration.ofSeconds(60), serverProperties.getTomcat().getKeepAliveTimeout());
    }

    @Test
    void virtualThreadsFicamDesligadas() {
        assertNotEquals("true", env.getProperty("spring.threads.virtual.enabled"));
    }

    @Test
    void portaEHikariPadrao() throws Exception {
        assertEquals("8086", env.getProperty("server.port"));
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            p.load(in);
        }
        assertEquals("${SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE:250}", p.getProperty("spring.datasource.hikari.maximum-pool-size"));
    }
}
