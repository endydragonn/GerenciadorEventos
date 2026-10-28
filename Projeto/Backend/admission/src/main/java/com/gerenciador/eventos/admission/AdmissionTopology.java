package com.gerenciador.eventos.admission;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia da fila de admissão (issue #42), declarada pelo próprio serviço (o {@code RabbitAdmin} do Spring Boot declara ao conectar):
 * exchange topic durável {@code admission}, routing key {@code event.<eventId>}, ligada com {@code event.*} a UMA fila durável
 * {@code admission.requests} com {@code x-message-ttl} (tempo máximo na fila), {@code x-max-length} + {@code reject-publish} (fila cheia =
 * nack do broker ao publicador) e dead-letter para {@code admission.dlx}, que entrega na fila {@code admission.timed_out}.
 *
 * <p><b>Uma fila, não uma por evento</b>: o worker pool é um teto GLOBAL de CAS simultâneos (o que a ADR controla), e filas por evento
 * exigiriam declarar/escutar filas dinamicamente e limpá-las; a routing key por evento deixa a porta aberta. A ordem por evento não é
 * garantida (a corretude é do CAS). Os nomes levam o prefixo {@code admission.amqp.prefix} (default vazio): os testes usam um prefixo
 * por classe, nunca as do serviço. A {@code admission.timed_out} tem o mesmo {@code x-max-length} (drop-head). Mudar o TTL ou o tamanho de uma fila que já existe exige apagá-la
 * (o broker não tem volume: reiniciá-lo basta); a redeclaração com outros argumentos falha com PRECONDITION_FAILED.</p>
 */
@Configuration
public class AdmissionTopology {

    static final String ROTA_TIMED_OUT = "timed_out";

    private final String prefixo;
    private final int ttlMs;
    private final int tamanhoMaximo;

    public AdmissionTopology(@Value("${admission.amqp.prefix:}") String prefixo,
            @Value("${admission.queue.ttl-ms:30000}") int ttlMs,
            @Value("${admission.queue.max-length:100000}") int tamanhoMaximo) {
        this.prefixo = prefixo;
        this.ttlMs = ttlMs;
        this.tamanhoMaximo = tamanhoMaximo;
    }

    /** Nome do exchange das requisições (publicação). */
    String exchange() {
        return prefixo + "admission";
    }

    /** Chave de roteamento da mensagem de um evento. */
    static String rota(long eventId) {
        return "event." + eventId;
    }

    @Bean
    TopicExchange admissionExchange() {
        return new TopicExchange(exchange(), true, false);
    }

    @Bean
    DirectExchange admissionDlx() {
        return new DirectExchange(prefixo + "admission.dlx", true, false);
    }

    @Bean
    Queue admissionRequests() {
        return QueueBuilder.durable(prefixo + "admission.requests")
                .ttl(ttlMs)
                .maxLength(tamanhoMaximo)
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .deadLetterExchange(prefixo + "admission.dlx")
                .deadLetterRoutingKey(ROTA_TIMED_OUT)
                .build();
    }

    @Bean
    Queue admissionTimedOut() {
        // com o listener da dead-letter parado a fila não cresce sem teto: estourou, descarta a mais velha (drop-head, o padrão). A
        // reconciliação é best-effort (o queued vencido já vale como timed_out em toda leitura)
        return QueueBuilder.durable(prefixo + "admission.timed_out").maxLength(tamanhoMaximo).build();
    }

    @Bean
    Binding admissionRequestsBinding(TopicExchange admissionExchange, Queue admissionRequests) {
        return BindingBuilder.bind(admissionRequests).to(admissionExchange).with("event.*");
    }

    @Bean
    Binding admissionTimedOutBinding(DirectExchange admissionDlx, Queue admissionTimedOut) {
        return BindingBuilder.bind(admissionTimedOut).to(admissionDlx).with(ROTA_TIMED_OUT);
    }
}
