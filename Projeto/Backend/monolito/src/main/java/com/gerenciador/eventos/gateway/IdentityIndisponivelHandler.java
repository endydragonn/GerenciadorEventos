package com.gerenciador.eventos.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.gerenciador.eventos.identityclient.api.IdentityUnavailableException;

/**
 * Falhas do serviço identity no {@code /bff/**} (issue #36, spec "Cliente"): {@link IdentityUnavailableException} (rede, timeout,
 * 5xx, 404 sem marcador) vira <b>503 com corpo genérico</b> e nunca vira 404, 401 ("senha errada") nem 500.
 *
 * <p><b>O log leva só a classe, nunca a mensagem nem a causa:</b> a mensagem da exceção do cliente carrega a URI chamada, e
 * {@code GET /internal/users/by-email?email=...} põe o e-mail do usuário nela (dado pessoal no log). Diferente do
 * {@code CatalogIndisponivelHandler}, que não tem esse risco.</p>
 *
 * <p>Um advice só vê o que o controller deixa escapar: os {@code catch (RuntimeException)}/{@code catch (Exception)} dos controllers
 * relançam a {@link IdentityUnavailableException} antes (senão ela viraria o 404/400/500 de lá).</p>
 */
@RestControllerAdvice
public class IdentityIndisponivelHandler {

    private static final Logger log = LoggerFactory.getLogger(IdentityIndisponivelHandler.class);

    private static final MediaType TEXTO_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    static final String INDISPONIVEL = "Serviço de usuários indisponível no momento. Tente novamente em instantes.";

    @ExceptionHandler(IdentityUnavailableException.class)
    ResponseEntity<String> indisponivel(IdentityUnavailableException ex) {
        log.warn("[gateway] serviço identity indisponível ({})", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(TEXTO_UTF8).body(INDISPONIVEL);
    }
}
