package com.gerenciador.eventos.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.gerenciador.eventos.catalogclient.api.CatalogUnavailableException;
import com.gerenciador.eventos.catalogclient.api.EventoNaoEncontradoException;

/**
 * Falhas do serviço catalog no {@code /bff/**} (issue #35, spec "Erros e contrato"). {@link CatalogUnavailableException} (rede,
 * timeout, 5xx do catalog) vira <b>503 com corpo genérico</b>: a mensagem da exceção traz URL/host internos e o corpo do erro do
 * serviço, e só vai para o log. Nunca vira 404 nem "Evento não encontrado". {@link EventoNaoEncontradoException} que escape de um
 * controller vira 404.
 *
 * <p>Um advice só vê o que o controller deixa escapar: os {@code catch (RuntimeException)} dos controllers relançam a
 * {@link CatalogUnavailableException} antes (senão ela viraria o 404/500 de lá).</p>
 */
@RestControllerAdvice
public class CatalogIndisponivelHandler {

    private static final Logger log = LoggerFactory.getLogger(CatalogIndisponivelHandler.class);

    static final String INDISPONIVEL = "Serviço de eventos indisponível no momento. Tente novamente em instantes.";

    /** Criar evento com o catalog fora/lento: o evento PODE ter sido gravado (timeout depois do commit). */
    static final String CRIACAO_INCERTA = "Não foi possível confirmar a criação do evento (resultado incerto): confira em Meus "
            + "eventos antes de tentar de novo.";

    @ExceptionHandler(CatalogUnavailableException.class)
    ResponseEntity<String> indisponivel(CatalogUnavailableException ex) {
        return resposta(ex, INDISPONIVEL);
    }

    @ExceptionHandler(EventoNaoEncontradoException.class)
    ResponseEntity<Void> naoEncontrado(EventoNaoEncontradoException ex) {
        return ResponseEntity.notFound().build();
    }

    /** 503 genérico, com o detalhe só no log do gateway. */
    static ResponseEntity<String> resposta(CatalogUnavailableException ex, String mensagem) {
        log.warn("[gateway] serviço catalog indisponível: {}", ex.toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.TEXT_PLAIN).body(mensagem);
    }
}
