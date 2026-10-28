package com.gerenciador.eventos.admission;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;

/**
 * Conexão ao RabbitMQ REAL dos testes (container {@code gerenciador-rabbitmq} na rede {@code gerenciador-net}; o {@code run-tests.sh}
 * repassa {@code RABBITMQ_HOST=rabbitmq} e, se exportadas na shell, {@code RABBITMQ_USER}/{@code RABBITMQ_PASSWORD}). Mesmas variáveis do
 * {@code application.properties}, então os testes Spring e os sem Spring falam com o mesmo broker.
 */
final class RabbitDeTeste {

    private RabbitDeTeste() { }

    static CachingConnectionFactory fabrica() {
        CachingConnectionFactory f = new CachingConnectionFactory(System.getenv().getOrDefault("RABBITMQ_HOST", "localhost"),
                Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672")));
        f.setUsername(System.getenv().getOrDefault("RABBITMQ_USER", ""));
        f.setPassword(System.getenv().getOrDefault("RABBITMQ_PASSWORD", ""));
        return f;
    }

    /** Prefixo desta JVM + o sufixo da classe de teste (o mesmo que a propriedade {@code admission.amqp.prefix} dela resolve). */
    static String prefixo(String sufixo) {
        return System.getProperty("admission.test.prefix") + sufixo;
    }
}
