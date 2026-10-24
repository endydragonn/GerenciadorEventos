package com.gerenciador.eventos.catalog;

import java.sql.SQLException;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
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

import com.gerenciador.eventos.catalogclient.api.EventoNaoEncontradoException;

/**
 * Status HTTP da API interna do catalog (issue #35, spec "Erros e contrato"); o corpo de toda resposta de erro é a MENSAGEM em
 * texto puro (o {@code CatalogClient} a repassa como mensagem da exceção). 400 = regra de negócio
 * ({@link IllegalArgumentException}) ou pedido malformado, e também o {@code 23505} SÓ da constraint {@code Event_event_name_key} (dois creates
 * concorrentes com o mesmo nome passam ambos pela pré-checagem; o segundo bate na constraint); 404 = evento inexistente; 409 =
 * {@link IllegalStateException} (apagar evento com reserva, recusado pelo reservation; evento pré-ES; conflito de versão
 * esgotado); 500 = o resto.
 */
@RestControllerAdvice
public class InternalApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(InternalApiExceptionHandler.class);

    /** SQLSTATE de {@code unique_violation} no Postgres. */
    private static final String UNIQUE_VIOLATION = "23505";
    /** Só ESTA constraint é "nome já existente"; outro 23505 (ex.: {@code Event_pkey}) é falha interna, 500. */
    private static final String UNIQUE_DO_NOME = "Event_event_name_key";

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

    @ExceptionHandler(EventoNaoEncontradoException.class)
    ResponseEntity<String> naoEncontrado(EventoNaoEncontradoException ex) {
        return texto(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<String> conflito(IllegalStateException ex) {
        return texto(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<String> falha(RuntimeException ex) {
        if (violouUniqueDoNome(ex)) {
            return texto(HttpStatus.BAD_REQUEST, "Nome de evento já existente");
        }
        log.error("[catalog] falha na API interna: {}", ex.toString(), ex);
        return texto(HttpStatus.INTERNAL_SERVER_ERROR, String.valueOf(ex.getMessage()));
    }

    /**
     * O repository embrulha a {@link SQLException} numa {@link RuntimeException}: procura na cadeia de causas o 23505 cuja
     * constraint (do {@code ServerErrorMessage} do driver) é a do {@code UNIQUE (event_name)}.
     */
    private static boolean violouUniqueDoNome(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof PSQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                ServerErrorMessage detalhe = sql.getServerErrorMessage();
                return detalhe != null && UNIQUE_DO_NOME.equals(detalhe.getConstraint());
            }
        }
        return false;
    }

    private static ResponseEntity<String> texto(HttpStatus status, String mensagem) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(mensagem);
    }
}
