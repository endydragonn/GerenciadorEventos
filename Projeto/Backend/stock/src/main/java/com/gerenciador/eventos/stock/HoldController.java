package com.gerenciador.eventos.stock;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gerenciador.eventos.stockclient.api.HoldRequest;
import com.gerenciador.eventos.stockclient.api.HoldResponse;
import com.gerenciador.eventos.stockclient.api.ReleaseRequest;

/**
 * API INTERNA dos holds (issue #47), chamada pelo reservation (orquestrador da saga). Erros: 400 regra de negócio ou pedido malformado,
 * 409 hold já liberado, 500 o resto ({@link InternalApiExceptionHandler}).
 */
@RestController
@RequestMapping("/internal/holds")
public class HoldController {

    private final HoldService service;

    public HoldController(HoldService service) {
        this.service = service;
    }

    /** Reserva a vaga: 201 com o hold novo, 200 na repetição de um hold ainda {@code held}. */
    @PostMapping
    public ResponseEntity<HoldResponse> reservar(@RequestBody HoldRequest pedido) {
        HoldService.Reservado r = service.reservar(pedido);
        return ResponseEntity.status(r.criado() ? HttpStatus.CREATED : HttpStatus.OK).body(r.resposta());
    }

    /** Libera o hold; sempre 200 (idempotente). */
    @PostMapping("/{holdId}/release")
    public ResponseEntity<Void> liberar(@PathVariable UUID holdId, @RequestBody ReleaseRequest pedido) {
        service.liberar(holdId, pedido.eventId(), pedido.userId());
        return ResponseEntity.ok().build();
    }
}
