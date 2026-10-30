package com.gerenciador.eventos.admission;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionBlockedEvent;
import org.springframework.amqp.rabbit.connection.ConnectionUnblockedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Estado "o broker bloqueou a conexão" (alarme de memória ou de disco do RabbitMQ), issue #42: o Spring AMQP publica
 * {@code ConnectionBlockedEvent}/{@code ConnectionUnblockedEvent} (o {@code BlockedListener} do cliente). Com o broker bloqueado a
 * publicação ficaria presa; a {@link FilaDeAdmissao} não publica (desfaz a entrada e o chamador segue síncrono).
 */
@Component
public class BrokerBloqueado {

    private static final Logger log = LoggerFactory.getLogger(BrokerBloqueado.class);

    private final AtomicBoolean bloqueado = new AtomicBoolean();

    @EventListener
    void bloqueou(ConnectionBlockedEvent evento) {
        if (bloqueado.compareAndSet(false, true)) {
            log.warn("[admission] RabbitMQ bloqueou a conexão ({}): sem publicar na fila até desbloquear", evento.getReason());
        }
    }

    @EventListener
    void desbloqueou(ConnectionUnblockedEvent evento) {
        if (bloqueado.compareAndSet(true, false)) {
            log.warn("[admission] RabbitMQ desbloqueou a conexão");
        }
    }

    boolean estaBloqueado() {
        return bloqueado.get();
    }
}
