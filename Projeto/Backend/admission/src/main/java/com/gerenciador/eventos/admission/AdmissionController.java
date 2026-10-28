package com.gerenciador.eventos.admission;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gerenciador.eventos.admissionclient.api.AdmissionRequest;
import com.gerenciador.eventos.admissionclient.api.AdmissionResult;
import com.gerenciador.eventos.admissionclient.api.AdmissionStatus;

/**
 * API interna do admission (issue #41), só na rede {@code gerenciador-net} e sempre atrás do {@link InternalTokenFilter}.
 * {@code POST /internal/admissions} conta a tentativa e devolve o modo (sempre {@code sync} na #41);
 * {@code GET /internal/admissions/events/{eventId}/rpm} devolve o RPM atual do evento sem contar; {@code GET /internal/admissions/result}
 * (#42) devolve o resultado da mensagem da fila de admissão (200) ou 404 sem corpo.
 */
@RestController
@RequestMapping("/internal/admissions")
public class AdmissionController {

    private final AdmissionService service;
    private final FilaDeAdmissao fila;

    public AdmissionController(AdmissionService service, FilaDeAdmissao fila) {
        this.service = service;
        this.fila = fila;
    }

    @PostMapping
    public AdmissionResult admit(@RequestBody AdmissionRequest pedido) {
        return service.admit(pedido);
    }

    @GetMapping("/events/{eventId}/rpm")
    public RpmDoEvento rpm(@PathVariable long eventId) {
        return new RpmDoEvento(eventId, service.rpm(eventId));
    }

    /** O resultado da chave do usuário na fila (um {@code queued} vencido vale como {@code timed_out}); 404 sem corpo se não há. */
    @GetMapping("/result")
    public ResponseEntity<AdmissionStatus> resultado(@RequestParam long userId, @RequestParam String idempotencyKey) {
        if (userId <= 0 || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("userId e idempotencyKey são obrigatórios");
        }
        return fila.consultar(userId, idempotencyKey).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Corpo de {@code GET .../rpm}. */
    public record RpmDoEvento(long eventId, long rpm) { }
}
