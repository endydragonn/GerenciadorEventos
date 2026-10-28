package com.gerenciador.eventos.admission;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

/**
 * A topologia declarada pelo serviço (issue #42), sem broker: exchange topic durável {@code admission}, fila durável
 * {@code admission.requests} com TTL, tamanho máximo, {@code reject-publish} e dead-letter para a {@code admission.dlx}, que entrega na
 * {@code admission.timed_out}; o prefixo vem da configuração. (O efeito de cada argumento é provado com o broker real nos outros testes:
 * TTL e dead-letter no {@code AdmissionDeadLetterTest}, tamanho e {@code reject-publish} no {@code FilaCheiaTest}.)
 */
class AdmissionTopologyTest {

    @Test
    void filaDeRequisicoesTemTtlTamanhoRejectPublishEDeadLetter() {
        AdmissionTopology t = new AdmissionTopology("", 30_000, 100_000);

        Queue fila = t.admissionRequests();
        Map<String, Object> args = fila.getArguments();

        assertEquals("admission.requests", fila.getName());
        assertTrue(fila.isDurable());
        assertFalse(fila.isAutoDelete());
        Queue mortas = t.admissionTimedOut();
        assertEquals(100_000, ((Number) mortas.getArguments().get("x-max-length")).intValue(), "a dead-letter não cresce sem teto");
        assertFalse(mortas.getArguments().containsKey("x-overflow"), "estourou: descarta a mais velha (drop-head)");
        assertEquals(30_000, ((Number) args.get("x-message-ttl")).intValue());
        assertEquals(100_000, ((Number) args.get("x-max-length")).intValue());
        assertEquals("reject-publish", args.get("x-overflow"));
        assertEquals("admission.dlx", args.get("x-dead-letter-exchange"));
        assertEquals("timed_out", args.get("x-dead-letter-routing-key"));
    }

    @Test
    void exchangeTopicDuravelComAChaveDoEventoEFilaDaDeadLetter() {
        AdmissionTopology t = new AdmissionTopology("", 30_000, 100_000);

        TopicExchange exchange = t.admissionExchange();
        Binding requisicoes = t.admissionRequestsBinding(exchange, t.admissionRequests());
        DirectExchange dlx = t.admissionDlx();
        Binding mortas = t.admissionTimedOutBinding(dlx, t.admissionTimedOut());

        assertEquals("admission", exchange.getName());
        assertTrue(exchange.isDurable());
        assertEquals("event.*", requisicoes.getRoutingKey());
        assertEquals("admission.requests", requisicoes.getDestination());
        assertEquals("admission.dlx", dlx.getName());
        assertEquals("timed_out", mortas.getRoutingKey());
        assertEquals("admission.timed_out", mortas.getDestination());
        assertEquals("event.42", AdmissionTopology.rota(42), "a routing key é a chave do evento");
        assertEquals("admission", t.exchange());
    }

    @Test
    void prefixoVemDaConfiguracaoEOsNomesDaDeadLetterAcompanham() {
        AdmissionTopology t = new AdmissionTopology("t1.fila-", 400, 2);

        Queue fila = t.admissionRequests();

        assertEquals("t1.fila-admission.requests", fila.getName());
        assertEquals("t1.fila-admission.dlx", fila.getArguments().get("x-dead-letter-exchange"));
        assertEquals("t1.fila-admission.timed_out", t.admissionTimedOut().getName());
        assertEquals("t1.fila-admission", t.exchange());
        assertEquals("t1.fila-admission", t.admissionExchange().getName());
    }
}
