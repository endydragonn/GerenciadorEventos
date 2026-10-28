package com.gerenciador.eventos.admission;

import org.springframework.stereotype.Service;

import com.gerenciador.eventos.admissionclient.api.AdmissionRequest;
import com.gerenciador.eventos.admissionclient.api.AdmissionResult;

/**
 * Regra do {@code POST /internal/admissions} (issue #41): valida o pedido, conta a tentativa no evento e devolve o modo. Na #41 o
 * modo é sempre {@code sync}. Redis fora ou lento (timeout de 200 ms) PROPAGA a falha: o {@link InternalApiExceptionHandler} responde 503
 * e o fail-open é do CLIENTE (o gateway segue síncrono). Devolver 200 {@code sync} aqui esconderia a falha do disjuntor do gateway, que
 * nunca abriria e pagaria os 200 ms do Redis travado em TODA reserva.
 */
@Service
public class AdmissionService {

    private static final int MAX_CHAVE = 255;

    private final RpmCounter contador;

    public AdmissionService(RpmCounter contador) {
        this.contador = contador;
    }

    public AdmissionResult admit(AdmissionRequest pedido) {
        validar(pedido);
        return AdmissionResult.sync(contador.contar(pedido.eventId()));
    }

    /** RPM do evento (só leitura). Falha do Redis propaga (503). */
    public long rpm(long eventId) {
        if (eventId <= 0) {
            throw new IllegalArgumentException("eventId deve ser positivo");
        }
        return contador.rpm(eventId);
    }

    /** As regras do pedido (a da Idempotency-Key é a do reservation). Também usada por {@link FilaDeAdmissao#enfileirar}. */
    static void validar(AdmissionRequest p) {
        if (p == null) {
            throw new IllegalArgumentException("Pedido vazio");
        }
        if (p.eventId() == null || p.eventId() <= 0) {
            throw new IllegalArgumentException("eventId é obrigatório e deve ser positivo");
        }
        if (p.userId() == null || p.userId() <= 0) {
            throw new IllegalArgumentException("userId é obrigatório e deve ser positivo");
        }
        if (p.idempotencyKey() == null || p.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key é obrigatória");
        }
        if (p.idempotencyKey().length() > MAX_CHAVE) {
            throw new IllegalArgumentException("Idempotency-Key deve ter no máximo 255 caracteres");
        }
        if (!"cas".equals(p.path()) && !"naive".equals(p.path())) {
            throw new IllegalArgumentException("path deve ser cas ou naive");
        }
    }
}
