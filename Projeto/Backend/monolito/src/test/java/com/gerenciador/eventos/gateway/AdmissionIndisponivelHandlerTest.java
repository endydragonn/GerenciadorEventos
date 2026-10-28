package com.gerenciador.eventos.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.gerenciador.eventos.admissionclient.api.AdmissionUnavailableException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * O 503 da consulta de estado da fila com o admission fora (issue #43): corpo genérico (sem URI, usuário nem chave) e WARN com limite de taxa:
 * com cada cliente enfileirado consultando a cada segundo, 50 falhas seguidas geram UMA linha (só a classe da causa, nunca a mensagem).
 */
class AdmissionIndisponivelHandlerTest {

    @Test
    void respondeGenericoELogaUmaLinhaPorIntervalo() {
        Logger logger = (Logger) LoggerFactory.getLogger(AdmissionIndisponivelHandler.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            AdmissionIndisponivelHandler handler = new AdmissionIndisponivelHandler();
            AdmissionUnavailableException ex = new AdmissionUnavailableException(
                    "consulta http://admission:8085/internal/admissions/result?userId=42&idempotencyKey=segredo-do-usuario",
                    new java.net.ConnectException("recusada"));

            ResponseEntity<String> r = null;
            for (int i = 0; i < 50; i++) {
                r = handler.indisponivel(ex);
            }

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, r.getStatusCode());
            assertEquals(AdmissionIndisponivelHandler.INDISPONIVEL, r.getBody());
            var avisos = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
            assertEquals(1, avisos.size(), "50 falhas no mesmo instante: uma linha só");
            String linha = avisos.get(0).getFormattedMessage();
            assertTrue(linha.contains("ConnectException"), linha);
            assertFalse(linha.contains("segredo-do-usuario") || linha.contains("userId=42"), "a mensagem da exceção (URI) não vai para o log: " + linha);
        } finally {
            logger.detachAppender(logs);
        }
    }
}
