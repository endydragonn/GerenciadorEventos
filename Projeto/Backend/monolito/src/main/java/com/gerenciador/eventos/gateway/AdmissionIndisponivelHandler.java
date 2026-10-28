package com.gerenciador.eventos.gateway;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.gerenciador.eventos.admissionclient.api.AdmissionUnavailableException;

/**
 * O serviço admission não respondeu à consulta de estado da fila (issue #43, {@code GET /bff/event-wallets/admission}): 503 com corpo
 * genérico. (A contagem do {@code POST} nunca chega aqui: o {@code AdmissionClient} faz fail-open e a reserva segue síncrona.) O log leva só
 * a classe: a mensagem da exceção carrega a URI, com o id do usuário e a chave de idempotência. Com o admission fora cada cliente enfileirado
 * consulta a cada {@code Retry-After}, então o WARN tem limite de taxa: no máximo 1 linha a cada 10 s, com a contagem acumulada (como o do
 * {@code AdmissionClient}; o ArchUnit do gateway só permite o pacote {@code admissionclient.api}, então o limitador é local).
 */
@RestControllerAdvice
public class AdmissionIndisponivelHandler {

    private static final Logger log = LoggerFactory.getLogger(AdmissionIndisponivelHandler.class);

    private static final MediaType TEXTO_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    private static final long INTERVALO_NANOS = Duration.ofSeconds(10).toNanos();

    static final String INDISPONIVEL = "Serviço de admissão indisponível no momento. Tente novamente em instantes.";

    private final AtomicInteger suprimidas = new AtomicInteger();
    private final AtomicLong ultimoLog = new AtomicLong(System.nanoTime() - INTERVALO_NANOS);

    @ExceptionHandler(AdmissionUnavailableException.class)
    ResponseEntity<String> indisponivel(AdmissionUnavailableException ex) {
        long agora = System.nanoTime();
        long ultimo = ultimoLog.get();
        if (agora - ultimo >= INTERVALO_NANOS && ultimoLog.compareAndSet(ultimo, agora)) {
            log.warn("[gateway] serviço admission indisponível na consulta ({}) [ocorrências desde o último log: {}]",
                    ex.getCause() == null ? ex.getClass().getSimpleName() : ex.getCause().getClass().getSimpleName(),
                    suprimidas.getAndSet(0) + 1);
        } else {
            suprimidas.incrementAndGet();
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(TEXTO_UTF8).body(INDISPONIVEL);
    }
}
