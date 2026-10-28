package com.gerenciador.eventos.identity;

import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.gerenciador.eventos.identityclient.IdentityClient;
import com.gerenciador.eventos.shared.events.EventStore;

/**
 * Trava a configuração do serviço identity (issue #36, spec "Pool"): Tomcat 300, Hikari 100 com {@code connection-timeout} 5 s
 * (1000 + 10 + 3 + 100 + 100 = 1213 <= {@code POOLER_MAX_CLIENT_CONN} 1500), porta 8084, keep-alive sem teto, virtual threads
 * desligadas, event store no schema {@code identity}, consumer desligado, expiração e emissor do JWT. Os defaults do Hikari são
 * lidos do {@code application.properties} cru: o env do {@code run-tests.sh} (o {@code .env}) pode sobrescrever o valor efetivo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GlobalDbTruncator.class)
public class IdentityConfigTest {

    @Autowired private ServerProperties serverProperties;
    @Autowired private Environment env;
    @Autowired private EventStore eventStore;
    @Autowired private ApplicationContext ctx;
    @LocalServerPort private int portaDoTeste;

    private static Properties propriedades() throws Exception {
        Properties p = new Properties();
        try (InputStream in = IdentityConfigTest.class.getResourceAsStream("/application.properties")) {
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
    void portaPadraoEhOitentaEOitentaEQuatro() throws Exception {
        assertEquals("8084", propriedades().getProperty("server.port"));
        assertTrue(portaDoTeste > 0);
    }

    @Test
    void hikariPadraoEhCemComConnectionTimeoutDeCincoSegundos() throws Exception {
        Properties p = propriedades();
        assertEquals("${SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE:100}", p.getProperty("spring.datasource.hikari.maximum-pool-size"));
        assertEquals("${SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT:5000}", p.getProperty("spring.datasource.hikari.connection-timeout"));
    }

    @Test
    void eventStoreNoSchemaIdentityEConsumerDesligado() {
        assertEquals("identity", eventStore.schema());
        assertEquals("identity.event_store", eventStore.tabela("event_store"));
        assertEquals("false", env.getProperty("events.consumer.enabled"));
    }

    @Test
    void jwtExpiraEmDezHorasPorPadraoEOEmissorEOGerenciadorIdentity() throws Exception {
        assertEquals("${JWT_EXPIRATION_SECONDS:36000}", propriedades().getProperty("jwt.expiration-seconds"));
        assertEquals("gerenciador-identity", env.getProperty("jwt.issuer"));
        assertEquals("${JWT_SIGNING_KEY_PATH:/var/lib/identity/jwt-signing-key.json}",
                propriedades().getProperty("jwt.signing-key-path"));
    }

    @Test
    void jwtSecretNaoExisteMais() throws Exception {
        assertNull(propriedades().getProperty("jwt.secret"), "JWT_SECRET deixou de ser lido por qualquer serviço");
    }

    /** O serviço não monta um cliente de si mesmo: o pacote identityclient fica fora do component scan. */
    @Test
    void naoTemBeanDoProprioClienteEOContratoSoEstaImplementadoUmaVez() {
        assertEquals(0, ctx.getBeanNamesForType(IdentityClient.class).length);
        assertEquals(1, ctx.getBeanNamesForType(com.gerenciador.eventos.identityclient.api.UserApi.class).length);
        assertEquals(1, ctx.getBeanNamesForType(com.gerenciador.eventos.identityclient.api.EmailVerificationApi.class).length);
        assertEquals(1, ctx.getBeanNamesForType(com.gerenciador.eventos.identityclient.api.MyWalletApi.class).length);
        assertEquals(1, ctx.getBeanNamesForType(com.gerenciador.eventos.identityclient.api.RecaptchaApi.class).length);
    }
}
