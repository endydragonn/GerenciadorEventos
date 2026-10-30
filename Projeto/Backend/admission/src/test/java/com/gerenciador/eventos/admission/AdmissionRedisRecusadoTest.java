package com.gerenciador.eventos.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Redis RECUSANDO a conexão (porta fechada), issue #41: {@code POST} e {@code GET} respondem 503 e rápido (comandos rejeitados na hora,
 * {@code REJECT_COMMANDS}), a falha que o disjuntor do gateway conta.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "internal.api.token=token-de-teste", "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1",
        "spring.data.redis.password="})
class AdmissionRedisRecusadoTest {

    @LocalServerPort private int porta;

    @Test
    void redisRecusandoConexaoDa503Rapido() {
        RedisForaSupport.post(porta, 5); // aquece (classes do Lettuce/Netty)
        RedisForaSupport.Medida post = RedisForaSupport.post(porta, 5);
        RedisForaSupport.Medida get = RedisForaSupport.getRpm(porta);

        assertEquals(503, post.status(), "Redis fora: 503 (o disjuntor do gateway precisa contar a falha)");
        assertTrue(post.ms() < 1_000, "a falha é rápida (conexão recusada): " + post.ms() + " ms");
        assertEquals(503, get.status());
        assertEquals(503, RedisForaSupport.getResultado(porta).status(), "a consulta do resultado da fila também: Redis fora = 503");
    }
}
