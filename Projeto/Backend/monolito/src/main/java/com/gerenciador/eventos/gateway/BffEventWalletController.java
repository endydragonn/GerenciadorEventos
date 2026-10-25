package com.gerenciador.eventos.gateway;

import java.util.ArrayList;
import java.util.List;

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

import com.gerenciador.eventos.catalogclient.api.CatalogUnavailableException;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.reservationclient.api.IdempotencyConflictException;

/**
 * Camada BFF única para inscrição em evento (event-wallet): addLink, confirm, validate,
 * removeLink, listByUser. Antes desta issue (#78) vivia em
 * {@code bff.BffApplication.EventWalletBffController} (a que o Flutter efetivamente chamava)
 * e era duplicada em {@code Controller.EventWalletController} sob {@code /api/event-wallets}
 * (sem chamador no frontend). Desde o PR #79 (issue #78), {@code /bff/event-wallets/**} exige
 * autenticação (deny-by-default); segue tirando {@code userId} do corpo/query sem validar
 * contra o usuário autenticado (IDOR — issue #57 trata isso, ainda não resolvido).
 *
 * <p>Desde a #33 a reserva é do serviço {@code reservation}: o {@link EventWalletGateway} confere carteira/evento e chama
 * a API interna dele por HTTP; o contrato deste controller não mudou.</p>
 */
@RestController
@RequestMapping("/bff/event-wallets")
public class BffEventWalletController {

    private final EventWalletGateway eventWalletService;

    public BffEventWalletController(EventWalletGateway eventWalletService) {
        this.eventWalletService = eventWalletService;
    }

    @PostMapping
    public ResponseEntity<?> addLink(@RequestBody EventWallet ew,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        List<String> errors = validateEventWalletBasic(ew);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(String.join("; ", errors));
        }
        try {
            EventWallet saved = eventWalletService.addLink(ew, idempotencyKey);
            // header aditivo: sem a chave na requisição, é a gerada pelo servidor (corpo JSON não muda)
            return ResponseEntity.status(HttpStatus.CREATED)
                    .header("Idempotency-Key", saved.getIdempotencyKey()).body(saved);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        } catch (IdempotencyConflictException ex) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ex.getMessage());
        } catch (CatalogUnavailableException ex) {
            throw ex; // a conferência do evento (exists) falhou: 503 genérico, nunca "Evento não encontrado"
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.getMessage());
        }
    }

    @PostMapping("/confirm")
    public ResponseEntity<?> confirmLink(@RequestBody EventWallet ew) {
        List<String> errors = validateEventWalletBasic(ew);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(String.join("; ", errors));
        }
        try {
            EventWallet confirmed = eventWalletService.confirm(ew.getUserId(), ew.getEventId());
            return ResponseEntity.ok(confirmed);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.getMessage());
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validateLink(@RequestBody EventWallet ew) {
        List<String> errors = validateEventWalletBasic(ew);
        String serviceErrors = eventWalletService.getValidationErrors(ew);
        if (!serviceErrors.isBlank()) {
            errors.add(serviceErrors);
        }
        if (errors.isEmpty()) {
            return ResponseEntity.ok("Vínculo válido");
        }
        return ResponseEntity.badRequest().body(String.join("; ", errors));
    }

    @DeleteMapping
    public ResponseEntity<Void> removeLink(@RequestParam Long userId, @RequestParam Long eventId) {
        eventWalletService.removeLink(userId, eventId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<EventWallet>> listByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(eventWalletService.listByUser(userId));
    }

    private List<String> validateEventWalletBasic(EventWallet ew) {
        List<String> errors = new ArrayList<>();
        if (ew.getUserId() == null || ew.getUserId() <= 0) {
            errors.add("user_id é obrigatório e deve ser positivo");
        }
        if (ew.getEventId() == null || ew.getEventId() <= 0) {
            errors.add("event_id é obrigatório e deve ser positivo");
        }
        return errors;
    }
}
