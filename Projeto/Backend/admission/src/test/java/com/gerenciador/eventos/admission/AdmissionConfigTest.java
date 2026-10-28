package com.gerenciador.eventos.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
