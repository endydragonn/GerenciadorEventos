package com.gerenciador.eventos.reservation;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gerenciador.eventos.reservationclient.api.EventWallet;

/**
 * API INTERNA de reservas (issue #33), chamada pelo gateway do monólito pela rede {@code gerenciador-net} (o container não
 * publica porta no host). Sem autenticação própria: quem autentica o usuário é o gateway, e o {@code userId} chega como
 * parâmetro EXPLÍCITO (corpo ou query), nunca inferido — a correção do IDOR #57 acontece só no gateway. Erros: o corpo é a
 * mensagem em texto puro e o status vem de {@link InternalApiExceptionHandler} (400 regra de negócio, 422 conflito de
 * {@code Idempotency-Key}, 500 o resto). O contrato do cliente está em {@code ReservationClient}.
 */
@RestController
@RequestMapping("/internal")
public class ReservationController {

    private final EventWalletService service;

    public ReservationController(EventWalletService service) {
        this.service = service;
    }

    /**
     * Reserva idempotente (201). {@code path=cas} (default): UPDATE condicional atômico em {@code eventcap}, uma chamada
     * só. {@code path=naive}: sem CAS (o gateway já leu a disponibilidade em {@link #disponibilidade}). A chave usada
     * (gerada ou recebida) volta no header {@code Idempotency-Key}.
     */
    @PostMapping("/reservations")
    public ResponseEntity<EventWallet> reservar(@RequestBody EventWallet ew,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(defaultValue = "cas") String path) {
        EventWallet saved = service.addLink(ew, idempotencyKey, "naive".equalsIgnoreCase(path));
        return ResponseEntity.status(HttpStatus.CREATED).header("Idempotency-Key", saved.getIdempotencyKey())
                .body(saved);
    }

    /** Resultado salvo da reserva com a chave (200), ou 404 se a chave não existe; outro evento => 422. */
    @GetMapping("/reservations/replay")
    public ResponseEntity<EventWallet> replay(@RequestParam Long userId, @RequestParam Long eventId,
            @RequestParam String idempotencyKey) {
        EventWallet salvo = service.replay(userId, eventId, idempotencyKey);
        return salvo == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(salvo);
    }

    @PostMapping("/reservations/confirm")
    public EventWallet confirmar(@RequestBody EventWallet ew) {
        return service.confirm(ew.getUserId(), ew.getEventId());
    }

    @DeleteMapping("/reservations")
    public ResponseEntity<Void> cancelar(@RequestParam Long userId, @RequestParam Long eventId) {
        service.removeLink(userId, eventId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/reservations/user/{userId}")
    public List<EventWallet> listarPorUsuario(@PathVariable Long userId) {
        return service.listByUser(userId);
    }

    @GetMapping("/reservations/active")
    public boolean ativa(@RequestParam Long userId, @RequestParam Long eventId) {
        return service.existsActive(userId, eventId);
    }

    /**
     * Disponibilidade DEFASADA (projeção assíncrona; só o braço naive usa): {@code {"available": n}}, {@code -1} =
     * ilimitado; 404 se o evento não tem {@code eventcap}.
     */
    @GetMapping("/events/{eventId}/availability")
    public ResponseEntity<Map<String, Integer>> disponibilidade(@PathVariable Long eventId) {
        Integer disponivel = service.disponibilidade(eventId);
        return disponivel == null
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).build()
                : ResponseEntity.ok(Map.of("available", disponivel));
    }
}
