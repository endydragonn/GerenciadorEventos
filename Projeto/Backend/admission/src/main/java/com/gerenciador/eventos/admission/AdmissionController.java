package com.gerenciador.eventos.admission;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gerenciador.eventos.admissionclient.api.AdmissionRequest;
import com.gerenciador.eventos.admissionclient.api.AdmissionResult;

/**
 * API interna do admission (issue #41), só na rede {@code gerenciador-net} e sempre atrás do {@link InternalTokenFilter}.
 * {@code POST /internal/admissions} conta a tentativa e devolve o modo (sempre {@code sync} na #41);
 * {@code GET /internal/admissions/events/{eventId}/rpm} devolve o RPM atual do evento sem contar.
 */
@RestController
@RequestMapping("/internal/admissions")
public class AdmissionController {

    private final AdmissionService service;

    public AdmissionController(AdmissionService service) {
        this.service = service;
    }

    @PostMapping
    public AdmissionResult admit(@RequestBody AdmissionRequest pedido) {
        return service.admit(pedido);
    }

    @GetMapping("/events/{eventId}/rpm")
    public RpmDoEvento rpm(@PathVariable long eventId) {
        return new RpmDoEvento(eventId, service.rpm(eventId));
    }

    /** Corpo de {@code GET .../rpm}. */
    public record RpmDoEvento(long eventId, long rpm) { }
}
