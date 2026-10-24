package com.gerenciador.eventos.catalog;

import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.catalogclient.CatalogClient;
import com.gerenciador.eventos.shared.events.EventStore;

/**
 * Trava a configuração do serviço catalog (issue #35, spec "Pool"): Tomcat 300, Hikari 100 com {@code connection-timeout} 5 s
 * (1000 + 350 + 3 + 100 = 1453 <= {@code POOLER_MAX_CLIENT_CONN} 1500), porta 8083, keep-alive sem teto, event store no schema
 * {@code catalog}, consumer desligado. Os defaults do Hikari são lidos do {@code application.properties} cru: o env do
 * {@code run-tests.sh} (o {@code .env}) pode sobrescrever o valor efetivo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GlobalDbTruncator.class)
public class CatalogConfigTest {

    @Autowired private ServerProperties serverProperties;
    @Autowired private Environment env;
    @Autowired private EventStore eventStore;
    @Autowired private ApplicationContext ctx;
    @LocalServerPort private int portaDoTeste;

    private static Properties propriedades() throws Exception {
        Properties p = new Properties();
        try (InputStream in = CatalogConfigTest.class.getResourceAsStream("/application.properties")) {
            p.load(in);
        }
        return p;
    }

    @Test
    void tomcatThreadsMaxEhTrezentosEKeepAliveSemTeto() {
        assertEquals(300, serverProperties.getTomcat().getThreads().getMax());
        assertEquals(-1, serverProperties.getTomcat().getMaxKeepAliveRequests());
        assertEquals(Duration.ofSeconds(60), serverProperties.getTomcat().getKeepAliveTimeout());
        assertNotEquals("true", env.getProperty("spring.threads.virtual.enabled"));
    }

    @Test
    void portaPadraoEhOitentaEOitentaETres() throws Exception {
        assertEquals("8083", propriedades().getProperty("server.port"));
        assertTrue(portaDoTeste > 0);
    }

    @Test
    void hikariPadraoEhCemComConnectionTimeoutDeCincoSegundos() throws Exception {
        Properties p = propriedades();
        assertEquals("${SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE:100}", p.getProperty("spring.datasource.hikari.maximum-pool-size"));
        assertEquals("${SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT:5000}", p.getProperty("spring.datasource.hikari.connection-timeout"));
    }

    @Test
    void eventStoreNoSchemaCatalogEConsumerDesligado() {
        assertEquals("catalog", eventStore.schema());
        assertEquals("catalog.event_store", eventStore.tabela("event_store"));
        assertEquals("false", env.getProperty("events.consumer.enabled"));
    }

    /** O serviço não monta um cliente de si mesmo: o pacote catalogclient fica fora do component scan. */
    @Test
    void naoTemBeanDoProprioCliente() {
        assertEquals(0, ctx.getBeanNamesForType(CatalogClient.class).length);
    }
}
