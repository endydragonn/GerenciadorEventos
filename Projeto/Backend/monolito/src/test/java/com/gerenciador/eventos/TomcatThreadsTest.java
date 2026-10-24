package com.gerenciador.eventos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;

/**
 * Issue #124: o pool de threads do Tomcat sobe de 200 (default do Spring Boot) para 300
 * ({@code server.tomcat.threads.max}, env {@code SERVER_TOMCAT_THREADS_MAX}). Mesma configuração
 * dos demais testes {@code @SpringBootTest} simples, então reaproveita o contexto em cache.
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
class TomcatThreadsTest {

    @Autowired
    private ServerProperties serverProperties;

    @Test
    void tomcatThreadsMax_eTrezentos() {
        assertEquals(300, serverProperties.getTomcat().getThreads().getMax());
    }
}
