package com.gerenciador.eventos.reservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.gerenciador.eventos.reservationclient.api.IdempotencyConflictException;

/**
 * Status HTTP da API interna: o corpo de toda resposta de erro é a MENSAGEM em texto puro (o {@code ReservationClient} a
 * repassa como mensagem da exceção). 400 = regra de negócio ({@link IllegalArgumentException}: "Evento lotado", "Vínculo
 * já existente"...), 400 também para pedido malformado, 422 = {@code Idempotency-Key} reusada com outro evento, 503 = saga indisponível ({@link SagaIndisponivelException}, #47: repetir é seguro), 500 = o resto.
 */
@RestControllerAdvice
public class InternalApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(InternalApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<String> regraDeNegocio(IllegalArgumentException ex) {
        return texto(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Pedido malformado (JSON ilegível, parâmetro ausente ou de tipo errado, content-type): erro do chamador, 400, não 500. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<String> pedidoMalformado(Exception ex) {
        return texto(HttpStatus.BAD_REQUEST, "Pedido malformado: " + ex.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<String> conflitoDeChave(IdempotencyConflictException ex) {
        return texto(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(SagaIndisponivelException.class)
    ResponseEntity<String> sagaIndisponivel(SagaIndisponivelException ex) {
        if (ex.getCause() != null) {
            log.warn("[reservation] {} ({})", ex.getMessage(), ex.getCause().toString());
        }
        return texto(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<String> falha(RuntimeException ex) {
        log.error("[reservation] falha na API interna: {}", ex.toString(), ex);
        return texto(HttpStatus.INTERNAL_SERVER_ERROR, String.valueOf(ex.getMessage()));
    }

    private static ResponseEntity<String> texto(HttpStatus status, String mensagem) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(mensagem);
    }
}
