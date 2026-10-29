package com.gerenciador.eventos.stock;

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

import com.gerenciador.eventos.stockclient.api.HoldLiberadoException;

/**
 * Status HTTP da API interna: o corpo de toda resposta de erro é a MENSAGEM em texto puro (o {@code StockClient} a repassa como
 * mensagem da exceção). 400 = regra de negócio ({@link IllegalArgumentException}: "Evento lotado", "Capacidade do evento não
 * configurada"...) e também pedido malformado, 409 = hold já liberado (lápide), 500 = o resto (inclusive banco fora).
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

    @ExceptionHandler(HoldLiberadoException.class)
    ResponseEntity<String> holdLiberado(HoldLiberadoException ex) {
        return texto(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<String> falha(RuntimeException ex) {
        log.error("[stock] falha na API interna: {}", ex.toString(), ex);
        return texto(HttpStatus.INTERNAL_SERVER_ERROR, String.valueOf(ex.getMessage()));
    }

    private static ResponseEntity<String> texto(HttpStatus status, String mensagem) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(mensagem);
    }
}
