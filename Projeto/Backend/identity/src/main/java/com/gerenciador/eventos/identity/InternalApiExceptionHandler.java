package com.gerenciador.eventos.identity;

import java.sql.SQLException;

import org.postgresql.util.PSQLException;
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

import com.gerenciador.eventos.identityclient.IdentityClient;
import com.gerenciador.eventos.identityclient.api.IdentityNaoEncontradoException;

/**
 * Status HTTP da API interna do identity (issue #36, spec "API interna"); o corpo de toda resposta de erro é a MENSAGEM em texto
 * puro (o {@code IdentityClient} a repassa como mensagem da exceção). <b>400</b> = regra de negócio ({@link IllegalArgumentException}) ou
 * pedido malformado (sempre texto de regra, nunca o JSON de erro padrão do Spring); <b>401</b> com o marcador
 * {@link IdentityClient#HEADER_CREDENCIAIS_INVALIDAS} = {@link CredenciaisInvalidasException} (login errado, código MFA errado ou
 * expirado, usuário inexistente na verificação MFA: NUNCA 404, senão o {@code /bff} viraria 500) — um 401 SEM o marcador é o filtro do
 * token interno; <b>404</b> com o marcador {@link IdentityNaoEncontradoException#HEADER} = usuário/carteira inexistente; <b>500</b> = o
 * resto (inclusive {@code SQLException} checada), com corpo GENÉRICO (o detalhe fica só no log do serviço, sem a mensagem do driver). Unique violation
 * ({@code 23505}) = <b>400</b> de regra, não 500.
 */
@RestControllerAdvice
public class InternalApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(InternalApiExceptionHandler.class);

    /** SQLSTATE de {@code unique_violation} no Postgres. */
    private static final String UNIQUE_VIOLATION = "23505";

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

    @ExceptionHandler(IdentityNaoEncontradoException.class)
    ResponseEntity<String> naoEncontrado(IdentityNaoEncontradoException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).header(IdentityNaoEncontradoException.HEADER, "true")
                .contentType(MediaType.TEXT_PLAIN).body(ex.getMessage());
    }

    @ExceptionHandler(CredenciaisInvalidasException.class)
    ResponseEntity<String> credenciaisInvalidas(CredenciaisInvalidasException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(IdentityClient.HEADER_CREDENCIAIS_INVALIDAS, "true")
                .contentType(MediaType.TEXT_PLAIN).body(ex.getMessage());
    }

    /** {@code SQLException} checada (os controllers de cadastro declaram {@code throws SQLException}): o mesmo tratamento do resto. */
    @ExceptionHandler(SQLException.class)
    ResponseEntity<String> falhaSql(SQLException ex) {
        return falha(ex);
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<String> falhaInesperada(RuntimeException ex) {
        return falha(ex);
    }

    /**
     * Violação de unique ({@code 23505}, na cadeia de causas: o repository embrulha a {@code SQLException} numa
     * {@code RuntimeException}) = conflito de regra, 400 com texto GENÉRICO (sem nome de constraint, coluna nem valor: isso é do
     * log, e o log também não leva a mensagem). Qualquer outra falha = 500 genérico. Falha de banco loga SÓ classe + SQLState +
     * constraint (a mensagem do driver traz {@code Key (email)=(...)}, dado pessoal); as demais, a exceção completa.
     */
    private ResponseEntity<String> falha(Exception ex) {
        SQLException sql = sqlException(ex);
        if (sql != null) {
            String constraint = sql instanceof PSQLException p && p.getServerErrorMessage() != null
                    ? p.getServerErrorMessage().getConstraint() : null;
            if (UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                log.warn("[identity] violação de unicidade na API interna (SQLState {}, constraint {})", sql.getSQLState(), constraint);
                return texto(HttpStatus.BAD_REQUEST, UserService.CADASTRO_DUPLICADO);
            }
            log.error("[identity] falha de banco na API interna: {} (SQLState {}, constraint {})", sql.getClass().getSimpleName(),
                    sql.getSQLState(), constraint);
        } else {
            log.error("[identity] falha na API interna: {}", ex.toString(), ex);
        }
        return texto(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno no serviço identity");
    }

    private static SQLException sqlException(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }

    private static ResponseEntity<String> texto(HttpStatus status, String mensagem) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(mensagem);
    }
}
