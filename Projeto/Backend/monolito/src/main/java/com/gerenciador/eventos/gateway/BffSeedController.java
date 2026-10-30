package com.gerenciador.eventos.gateway;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gerenciador.eventos.catalogclient.api.CatalogUnavailableException;
import com.gerenciador.eventos.catalogclient.api.Event;
import com.gerenciador.eventos.catalogclient.api.EventApi;
import com.gerenciador.eventos.identityclient.api.IdentityUnavailableException;
import com.gerenciador.eventos.identityclient.api.User;
import com.gerenciador.eventos.identityclient.api.UserApi;
import com.gerenciador.eventos.reservationclient.api.EventWallet;

/**
 * Popula o banco com dados de exemplo (uso em dev). Garante que senhas sejam
 * criptografadas ao passar pelo {@link UserApi}. Antes desta issue (#78) vivia em
 * {@code Controller.SeedController} sob {@code /api/seed}; comportamento inalterado.
 *
 * <p>Issue #35 (spec, aceite 7): <b>desligado por padrão</b>. Sem {@code SEED_ENABLED=true} ({@code seed.enabled}) o
 * {@code POST /bff/seed} responde 404 e nada é criado; a rota é pública e aceitaria {@code creator_id}/{@code isAdmin} de
 * qualquer um. Liga-se só no override de carga/dev ({@code carga/docker-compose.carga.yml}) e nos testes; ligado, o
 * {@code creator_id} do corpo é aceito (ferramenta de dev).</p>
 */
@RestController
@RequestMapping("/bff/seed")
public class BffSeedController {

    private final UserApi userService;
    private final EventApi eventService;
    private final EventWalletGateway eventWalletService;
    private final boolean habilitado;

    public BffSeedController(UserApi userService, EventApi eventService,
                              EventWalletGateway eventWalletService,
                              @Value("${seed.enabled:false}") boolean habilitado) {
        this.userService = userService;
        this.eventService = eventService;
        this.eventWalletService = eventWalletService;
        this.habilitado = habilitado;
    }

    /**
     * POST /bff/seed - Popular banco com dados de exemplo
     *
     * Body exemplo:
     * {
     *   "users": [
     *     {
     *       "name": "João Silva",
     *       "email": "joao@email.com",
     *       "password": "senha123",
     *       "fone": "11999999999",
     *       "birthDate": "1990-01-15",
     *       "isAdmin": false
     *     }
     *   ],
     *   "events": [
     *     {
     *       "creator_id": 1,
     *       "event_name": "Workshop de Java",
     *       "is_EAD": false,
     *       "address": "São Paulo",
     *       "event_date": "2025-12-01T14:00:00",
     *       "lot_quantity": 100,
     *       "quantity": 100,
     *       "description": "Workshop hands-on"
     *     }
     *   ],
     *   "enrollments": [
     *     {
     *       "userId": 2,
     *       "eventId": 1
     *     }
     *   ]
     * }
     */
    @PostMapping
    public ResponseEntity<?> seedDatabase(@RequestBody SeedData seedData) {
        if (!habilitado) {
            return ResponseEntity.notFound().build(); // SEED_ENABLED desligado (default): a rota "não existe"
        }
        Map<String, Object> result = new HashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        int usersCreated = 0;
        int eventsCreated = 0;
        int enrollmentsCreated = 0;

        // 1. Criar usuários (senhas serão criptografadas pelo UserService)
        if (seedData.getUsers() != null) {
            for (User user : seedData.getUsers()) {
                try {
                    userService.createUser(user);
                    usersCreated++;
                } catch (IllegalArgumentException e) {
                    // Usuário já existe ou erro de validação - registrar mas continuar
                    warnings.add("Usuário '" + user.getEmail() + "': " + e.getMessage());
                } catch (IdentityUnavailableException e) { // a mensagem traz URI com e-mail: fica fora da resposta
                    errors.add("Erro ao criar usuário '" + user.getEmail() + "': " + IdentityIndisponivelHandler.INDISPONIVEL);
                } catch (Exception e) {
                    errors.add("Erro ao criar usuário '" + user.getEmail() + "': " + e.getMessage());
                }
            }
        }

        // 2. Criar eventos
        if (seedData.getEvents() != null) {
            for (Event event : seedData.getEvents()) {
                try {
                    // o criador é do identity: conferido aqui (o catalog só guarda o id). Ferramenta de dev: o creator_id do
                    // corpo é aceito (issue #35), só com SEED_ENABLED ligado.
                    if (event.getCreator_id() != null && !userService.exists(event.getCreator_id())) {
                        throw new IllegalArgumentException("creator_id não existe");
                    }
                    eventService.createEvent(event);
                    eventsCreated++;
                } catch (IllegalArgumentException e) {
                    warnings.add("Evento '" + event.getEvent_name() + "': " + e.getMessage());
                } catch (CatalogUnavailableException e) { // a mensagem traz URL interna: fica no log, não na resposta
                    errors.add("Erro ao criar evento '" + event.getEvent_name() + "': " + CatalogIndisponivelHandler.INDISPONIVEL);
                } catch (IdentityUnavailableException e) { // a mensagem traz URI com e-mail: fica fora da resposta e do log
                    errors.add("Erro ao criar evento '" + event.getEvent_name() + "': " + IdentityIndisponivelHandler.INDISPONIVEL);
                } catch (Exception e) {
                    errors.add("Erro ao criar evento '" + event.getEvent_name() + "': " + e.getMessage());
                }
            }
        }

        // 3. Criar inscrições (walletevent)
        if (seedData.getEnrollments() != null) {
            for (EventWallet enrollment : seedData.getEnrollments()) {
                try {
                    // seed cria inscrições já confirmadas (reserva + confirmação)
                    eventWalletService.addLink(enrollment, null);
                    eventWalletService.confirm(enrollment.getUserId(), enrollment.getEventId());
                    enrollmentsCreated++;
                } catch (ReservaEnfileiradaException e) {
                    // evento em modo fila (#43): a reserva ficou na fila do admission (o seed não a confirma)
                    warnings.add("Inscrição (user:" + enrollment.getUserId() + ", event:" + enrollment.getEventId()
                            + "): enfileirada pelo admission (evento em modo fila); não confirmada pelo seed");
                } catch (IllegalArgumentException e) {
                    warnings.add("Inscrição (user:" + enrollment.getUserId() +
                               ", event:" + enrollment.getEventId() + "): " + e.getMessage());
                } catch (CatalogUnavailableException e) {
                    errors.add("Erro ao criar inscrição: " + CatalogIndisponivelHandler.INDISPONIVEL);
                } catch (Exception e) {
                    errors.add("Erro ao criar inscrição: " + e.getMessage());
                }
            }
        }

        // Montar resposta
        result.put("usersCreated", usersCreated);
        result.put("eventsCreated", eventsCreated);
        result.put("enrollmentsCreated", enrollmentsCreated);

        if (!warnings.isEmpty()) {
            result.put("warnings", warnings);
        }

        if (!errors.isEmpty()) {
            result.put("errors", errors);
            result.put("status", "partial_success");
            return ResponseEntity.status(HttpStatus.MULTI_STATUS).body(result);
        }

        result.put("status", "success");
        result.put("message", "Banco populado com sucesso!");
        return ResponseEntity.ok(result);
    }
}
