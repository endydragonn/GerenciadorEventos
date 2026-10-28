package com.gerenciador.eventos.gateway;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import com.gerenciador.eventos.gateway.security.HostAuthorizationApi;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.reservationclient.api.IdempotencyConflictException;

/**
 * Camada BFF única para inscrição em evento (event-wallet): addLink, confirm, validate,
 * removeLink, listByUser. Antes desta issue (#78) vivia em
 * {@code bff.BffApplication.EventWalletBffController} (a que o Flutter efetivamente chamava)
 * e era duplicada em {@code Controller.EventWalletController} sob {@code /api/event-wallets}
 * (sem chamador no frontend). Desde o PR #79 (issue #78), {@code /bff/event-wallets/**} exige
 * autenticação (deny-by-default).
 *
 * <p>Issue #57 (IDOR): o {@code userId} é o do token ({@link HostAuthorizationApi#currentUserId()}), nunca o do
 * corpo/query/path. Nesses três lugares ele é opcional: ausente vale o do token, igual segue e <b>diferente é 403</b>,
 * antes de qualquer validação ou chamada ao reservation (a negação não revela se o outro usuário existe). Vale para
 * addLink, confirm, validate, removeLink e listByUser. Desde a #36 o {@code userId} é o claim {@code uid} do JWT do identity (sem consulta
 * ao banco): um {@code uid} sem usuário não dá mais 401 aqui. O mesmo IDOR em {@code BffMyWalletController} é outra issue.</p>
 *
 * <p>Desde a #33 a reserva é do serviço {@code reservation}: o {@link EventWalletGateway} confere o evento e chama
 * a API interna dele por HTTP; o contrato deste controller não mudou.</p>
 */
@RestController
@RequestMapping("/bff/event-wallets")
public class BffEventWalletController {

    private static final Logger log = LoggerFactory.getLogger(BffEventWalletController.class);

    private static final ResponseEntity<String> NEGADO = ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body("Acesso negado: o usuário informado não é o usuário autenticado");

    private final EventWalletGateway eventWalletService;
    private final HostAuthorizationApi authorizationUtil;

    public BffEventWalletController(EventWalletGateway eventWalletService, HostAuthorizationApi authorizationUtil) {
        this.eventWalletService = eventWalletService;
        this.authorizationUtil = authorizationUtil;
    }

    @PostMapping
    public ResponseEntity<?> addLink(@RequestBody EventWallet ew,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        ResponseEntity<String> barrado = amarrarAoToken(ew);
        if (barrado != null) {
            return barrado;
        }
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
            log.error("Erro ao reservar userId={} eventId={}", ew.getUserId(), ew.getEventId(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErroInterno.MENSAGEM);
        }
    }

    @PostMapping("/confirm")
    public ResponseEntity<?> confirmLink(@RequestBody EventWallet ew) {
        ResponseEntity<String> barrado = amarrarAoToken(ew);
        if (barrado != null) {
            return barrado;
        }
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
            log.error("Erro ao confirmar reserva userId={} eventId={}", ew.getUserId(), ew.getEventId(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErroInterno.MENSAGEM);
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validateLink(@RequestBody EventWallet ew) {
        ResponseEntity<String> barrado = amarrarAoToken(ew);
        if (barrado != null) {
            return barrado;
        }
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
    public ResponseEntity<?> removeLink(@RequestParam(required = false) Long userId, @RequestParam Long eventId) {
        EventWallet ew = new EventWallet(userId, eventId);
        ResponseEntity<String> barrado = amarrarAoToken(ew);
        if (barrado != null) {
            return barrado;
        }
        eventWalletService.removeLink(ew.getUserId(), eventId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<?> listByUser(@PathVariable Long userId) {
        EventWallet ew = new EventWallet(userId, null);
        ResponseEntity<String> barrado = amarrarAoToken(ew);
        if (barrado != null) {
            return barrado;
        }
        return ResponseEntity.ok(eventWalletService.listByUser(ew.getUserId()));
    }

    /**
     * Issue #57: grava em {@code ew} o usuário do token. Devolve a resposta de recusa (403 se o {@code userId} informado
     * difere do dono do token; 401 se o token não traz usuário) ou {@code null} quando pode seguir.
     */
    private ResponseEntity<String> amarrarAoToken(EventWallet ew) {
        Long doToken;
        try {
            doToken = authorizationUtil.currentUserId();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Usuário autenticado não encontrado");
        }
        if (ew.getUserId() != null && !ew.getUserId().equals(doToken)) {
            return NEGADO;
        }
        ew.setUserId(doToken);
        return null;
    }

    private List<String> validateEventWalletBasic(EventWallet ew) {
        List<String> errors = new ArrayList<>();
        if (ew.getEventId() == null || ew.getEventId() <= 0) {
            errors.add("event_id é obrigatório e deve ser positivo");
        }
        return errors;
    }
}
