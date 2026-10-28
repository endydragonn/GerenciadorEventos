package com.gerenciador.eventos.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/** A configuração do serviço admission (issue #41): porta, Tomcat como o reservation, sem datasource, e o token obrigatório. */
@SpringBootTest(properties = "internal.api.token=token-de-teste")
class AdmissionConfigTest {

    @Autowired private Environment env;
    @Autowired private ApplicationContext contexto;
    @Autowired private org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;

    @Test
    void portaTomcatEKeepAliveComoOReservation() {
        assertEquals("8085", env.getProperty("server.port"));
        assertEquals("300", env.getProperty("server.tomcat.threads.max"));
        assertEquals("-1", env.getProperty("server.tomcat.max-keep-alive-requests"));
        assertEquals("60s", env.getProperty("server.tomcat.keep-alive-timeout"));
        assertNull(env.getProperty("spring.threads.virtual.enabled"), "virtual threads desligadas (padrão)");
    }

    @Test
    void contadorComOsDefaultsDaSpec() {
        assertEquals("50", env.getProperty("admission.bucket-ms"));
        assertEquals("200", env.getProperty("admission.window-ms"));
        assertEquals("200ms", env.getProperty("spring.data.redis.timeout"));
    }

    /** O gatilho (#43): X medido na #40, 1 réplica até a #111, entra em fila com 80% e volta com 70% e a espera vazia. */
    @Test
    void gatilhoComOsDefaultsDaSpec() {
        assertEquals("56000", env.getProperty("admission.x-rpm"));
        assertEquals("1", env.getProperty("admission.reservation-replicas"));
        assertEquals("0.8", env.getProperty("admission.queue-on"));
        assertEquals("0.7", env.getProperty("admission.sync-below"));
    }

    /** A fila de admissão (#42): defaults da spec; o worker é o teto de CAS simultâneos, prefetch 1, confirms + returns + mandatory. */
    @Test
    void filaDeAdmissaoComOsDefaultsDaSpec() {
        assertEquals("30000", env.getProperty("admission.queue.ttl-ms"));
        assertEquals("100000", env.getProperty("admission.queue.max-length"));
        assertEquals("32", env.getProperty("admission.worker.concurrency"));
        assertEquals("500", env.getProperty("admission.retry-backoff-ms"));
        assertEquals("5", env.getProperty("admission.max-waiting-per-user"));
        assertEquals("600", env.getProperty("admission.result-ttl-seconds"));
        assertEquals("200", env.getProperty("admission.publish.confirm-timeout-ms"));
        assertEquals("1", env.getProperty("spring.rabbitmq.listener.simple.prefetch"));
        assertEquals("correlated", env.getProperty("spring.rabbitmq.publisher-confirm-type"));
        assertEquals("true", env.getProperty("spring.rabbitmq.publisher-returns"));
        assertEquals("true", env.getProperty("spring.rabbitmq.template.mandatory"));
    }

    /** O publicador tem conexão própria (um alarme do broker não pode travar os acks dos workers) . */
    @Test
    void publicadorEmConexaoPropria() {
        assertTrue(rabbit.isUsePublisherConnection(), "RabbitTemplate.usePublisherConnection");
    }

    /** Sem JDBC no classpath e sem DataSource no contexto (o {@code --env-file} do run-tests injeta SPRING_DATASOURCE_*, então a property não prova nada). */
    @Test
    void semDatasource() {
        assertFalse(ClassUtils.isPresent("org.springframework.jdbc.core.JdbcTemplate", null), "sem spring-jdbc");
        assertFalse(ClassUtils.isPresent("com.zaxxer.hikari.HikariDataSource", null), "sem Hikari");
        assertEquals(0, contexto.getBeansOfType(javax.sql.DataSource.class).size(), "nenhum DataSource no contexto");
    }

    @Test
    void semTokenOServicoNaoSobe() {
        assertThrows(IllegalStateException.class, () -> new InternalTokenFilter(""));
        assertThrows(IllegalStateException.class, () -> new InternalTokenFilter(null));
    }
}
