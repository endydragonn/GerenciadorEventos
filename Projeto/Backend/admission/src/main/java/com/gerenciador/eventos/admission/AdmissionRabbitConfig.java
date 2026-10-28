package com.gerenciador.eventos.admission;

import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * O publicador usa uma conexão AMQP SEPARADA da dos consumers (issue #42). Com uma conexão só, um alarme de memória ou de disco do broker a
 * bloqueia (o broker para de ler o socket de quem publica) e os acks e nacks dos workers, que andam na mesma conexão, deixam de ser
 * processados: com prefetch 1 nenhum worker recebe mensagem nova e a fila não drena justo quando drenar liberaria a memória. Os eventos de
 * bloqueio ({@code ConnectionBlockedEvent}) continuam chegando ao {@link BrokerBloqueado}.
 *
 * <p>A topologia é declarada pela conexão dos consumers (o {@code RabbitAdmin} declara ao conectar); os testes sem consumers chamam
 * {@code RabbitAdmin.initialize()}.</p>
 */
@Configuration
public class AdmissionRabbitConfig {

    @Bean
    RabbitTemplateCustomizer publicadorEmConexaoPropria() {
        return template -> template.setUsePublisherConnection(true);
    }
}
