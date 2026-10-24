package com.gerenciador.eventos.shared.events;

import java.sql.SQLException;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.RetryListener;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * O retry de erro transitório do consumer não tem limite (e nunca vai ao DLT): sem o {@code RetryListener} um
 * consumer travado só apareceria como lag crescendo. A partir da N-ésima tentativa de uma mesma mensagem, cada falha
 * loga em ERROR (revisão do Gemini na #27).
 */
public class EventsKafkaConfigTest {

    private final Logger log = (Logger) LoggerFactory.getLogger(EventsKafkaConfig.class);
    private final ListAppender<ILoggingEvent> saida = new ListAppender<>();

    @BeforeEach
    void anexar() {
        saida.start();
        log.addAppender(saida);
    }

    @AfterEach
    void soltar() {
        log.detachAppender(saida);
    }

    @Test
    void logaEmErroSoAPartirDaNEsimaTentativa() {
        RetryListener listener = EventsKafkaConfig.logarErroAPartirDe(3);
        ConsumerRecord<String, String> rec = new ConsumerRecord<>("reservation.wallet", 0, 7L, "42", "{}");

        listener.failedDelivery(rec, new SQLException("conexão caiu"), 1);
        listener.failedDelivery(rec, new SQLException("conexão caiu"), 2);
        assertEquals(List.of(), saida.list, "antes da 3ª tentativa não loga em ERROR");

        listener.failedDelivery(rec, new SQLException("conexão caiu"), 3);
        listener.failedDelivery(rec, new SQLException("conexão caiu"), 4);
        assertEquals(2, saida.list.size(), "da 3ª em diante, uma linha por tentativa");
        ILoggingEvent ultima = saida.list.get(1);
        assertEquals(Level.ERROR, ultima.getLevel());
        assertTrue(ultima.getFormattedMessage().contains("reservation.wallet-0@7"), ultima.getFormattedMessage());
        assertTrue(ultima.getFormattedMessage().contains("tentativa 4"), ultima.getFormattedMessage());
    }
}
