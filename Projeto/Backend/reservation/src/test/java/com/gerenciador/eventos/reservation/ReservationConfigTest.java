package com.gerenciador.eventos.reservation;

import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.events.EventStore;

/**
 * Trava a configuração do CAMINHO QUENTE do experimento no serviço reservation (issue #33): os valores que o monólito tinha
 * no ponto 2 (Tomcat 300, Hikari 1000, virtual threads desligadas), para a Rodada 3 comparar só o efeito do salto de rede.
 * Mudar qualquer um muda a comparabilidade e a conta do pool do Supavisor (Backend/CLAUDE.md, "Serviço reservation").
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
public class ReservationConfigTest {

    @Autowired private ServerProperties serverProperties;
    @Autowired private Environment env;
    @Autowired private EventStore eventStore;

    @Test
    void tomcatThreadsMaxEhTrezentos() {
        assertEquals(300, serverProperties.getTomcat().getThreads().getMax());
    }

    /** Keep-alive sem teto de requisições (o default de 100 reconectaria sem parar o salto medido) e timeout > evict do cliente. */
    @Test
    void keepAliveNaoFechaAConexaoACadaCemRequisicoes() {
        assertEquals(-1, serverProperties.getTomcat().getMaxKeepAliveRequests());
        assertEquals(Duration.ofSeconds(60), serverProperties.getTomcat().getKeepAliveTimeout());
    }

    @Test
    void virtualThreadsFicamDesligadas() {
        assertNotEquals("true", env.getProperty("spring.threads.virtual.enabled"));
    }

    /** O default do Hikari é 1000 (o teste não pode ler o valor efetivo: o env do run-tests pode reduzi-lo para o Postgres direto). */
    @Test
    void hikariMaximoPadraoEhMil() throws Exception {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            p.load(in);
        }
        assertEquals("${SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE:1000}", p.getProperty("spring.datasource.hikari.maximum-pool-size"));
    }

    /** O event store do serviço é o do schema reservation (não lê o do monólito). */
    @Test
    void eventStoreApontaParaOSchemaReservation() {
        assertEquals("reservation", eventStore.schema());
        assertEquals("reservation.event_store", eventStore.tabela("event_store"));
    }
}
