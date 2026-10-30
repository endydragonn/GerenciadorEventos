package com.gerenciador.eventos.admission;

import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Conexão ao Redis REAL dos testes (container {@code gerenciador-redis} na rede {@code gerenciador-net}; o {@code run-tests.sh}
 * repassa {@code REDIS_HOST=redis} e {@code REDIS_PASSWORD}). Mesmas variáveis do {@code application.properties}, então os testes
 * Spring e os sem Spring falam com o mesmo Redis.
 */
final class RedisDeTeste {

    private RedisDeTeste() { }

    static StringRedisTemplate template() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("REDIS_HOST", "localhost"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        String senha = System.getenv("REDIS_PASSWORD");
        if (senha != null && !senha.isBlank()) {
            cfg.setPassword(RedisPassword.of(senha));
        }
        LettuceConnectionFactory fabrica = new LettuceConnectionFactory(cfg);
        fabrica.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(fabrica);
        redis.afterPropertiesSet();
        return redis;
    }

    /** Id de evento que nenhum outro teste usa (a chave do contador é por evento; o Redis é compartilhado entre rodadas). */
    static long eventoNovo() {
        return 1_000_000_000L + (System.nanoTime() & 0xFFFFFFFFL);
    }

    /** Id de usuário que nenhum outro teste usa (as esperas e os resultados da fila são por usuário; o Redis é compartilhado entre rodadas). */
    static long usuarioNovo() {
        return 2_000_000_000L + (System.nanoTime() & 0xFFFFFFFFL);
    }
}
