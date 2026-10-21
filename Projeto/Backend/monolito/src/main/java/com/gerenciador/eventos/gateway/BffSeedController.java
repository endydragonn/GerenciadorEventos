package com.gerenciador.eventos.gateway;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gerenciador.eventos.catalog.api.Event;
import com.gerenciador.eventos.catalog.api.EventApi;
import com.gerenciador.eventos.identity.api.User;
import com.gerenciador.eventos.identity.api.UserApi;
import com.gerenciador.eventos.reservation.api.EventWallet;
import com.gerenciador.eventos.reservation.api.EventWalletApi;

/**
 * Popula o banco com dados de exemplo (uso em dev). Garante que senhas sejam
 * criptografadas ao passar pelo {@link UserApi}. Antes desta issue (#78) vivia em
 * {@code Controller.SeedController} sob {@code /api/seed}; comportamento inalterado.
 */
@RestController
@RequestMapping("/bff/seed")
public class BffSeedController {

    private final UserApi userService;
    private final EventApi eventService;
    private final EventWalletApi eventWalletService;

    public BffSeedController(UserApi userService, EventApi eventService,
                              EventWalletApi eventWalletService) {
        this.userService = userService;
        this.eventService = eventService;
        this.eventWalletService = eventWalletService;
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
                } catch (Exception e) {
                    errors.add("Erro ao criar usuário '" + user.getEmail() + "': " + e.getMessage());
                }
            }
        }

        // 2. Criar eventos
        if (seedData.getEvents() != null) {
            for (Event event : seedData.getEvents()) {
                try {
                    eventService.createEvent(event);
                    eventsCreated++;
                } catch (IllegalArgumentException e) {
                    warnings.add("Evento '" + event.getEvent_name() + "': " + e.getMessage());
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
                    eventWalletService.addLink(enrollment);
                    eventWalletService.confirm(enrollment.getUserId(), enrollment.getEventId());
                    enrollmentsCreated++;
                } catch (IllegalArgumentException e) {
                    warnings.add("Inscrição (user:" + enrollment.getUserId() +
                               ", event:" + enrollment.getEventId() + "): " + e.getMessage());
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
