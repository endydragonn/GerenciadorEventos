package com.gerenciador.eventos.admission;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.gerenciador.eventos.admissionclient.LogComLimite;

/**
 * Status HTTP da API interna: o corpo de toda resposta de erro é a MENSAGEM em texto puro (o {@code AdmissionClient} a repassa
 * como mensagem da exceção). 400 = regra de negócio ({@link IllegalArgumentException}) e pedido malformado, 503 = Redis fora ou lento
 * (no POST e no GET: o fail-open é do cliente, e o 503 é o que o disjuntor do gateway conta), 500 = o resto (corpo genérico: o detalhe só
 * vai para o log). O log do 503 tem limite de taxa (no máximo 1 linha a cada 10 s, com a contagem acumulada).
 */
@RestControllerAdvice
public class InternalApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(InternalApiExceptionHandler.class);
    private final LogComLimite avisoRedis = LogComLimite.warn(log, Duration.ofSeconds(10).toNanos(), System::nanoTime);

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<String> regraDeNegocio(IllegalArgumentException ex) {
        return texto(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Pedido malformado (JSON ilegível, parâmetro ausente ou de tipo errado, content-type): erro do chamador, 400, não 500. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<String> pedidoMalformado(Exception ex) {
        return texto(HttpStatus.BAD_REQUEST, "Pedido malformado");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<String> redisFora(DataAccessException ex) {
        avisoRedis.registrar("[admission] Redis indisponível (" + ex + "): respondendo 503");
        return texto(HttpStatus.SERVICE_UNAVAILABLE, "Redis indisponível");
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<String> falha(RuntimeException ex) {
        log.error("[admission] falha na API interna: {}", ex.toString(), ex);
        return texto(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno no serviço admission");
    }

    private static ResponseEntity<String> texto(HttpStatus status, String mensagem) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(mensagem);
    }
}
