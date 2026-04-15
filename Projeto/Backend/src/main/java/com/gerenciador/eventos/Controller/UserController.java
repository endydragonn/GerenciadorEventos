package com.gerenciador.eventos.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gerenciador.eventos.POJO.User;
import com.gerenciador.eventos.Service.UserService;
import com.gerenciador.eventos.Service.EmailVerificationService;
import com.gerenciador.eventos.security.JwtUtil;
import java.util.HashMap;
import java.util.Map;
import java.sql.SQLException;

/**
 * Controller - endpoints REST da API
 * Camada de apresentação que recebe requisições HTTP
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * POST /api/users - Criar novo usuário
     * 
     * Body exemplo:
     * {
     *   "name": "João Silva",
     *   "email": "joao@email.com",
     *   "fone": "11999999999",
     *   "password": "senha123",
     *   "cpf": "12345678900",
     *   "birthDate": "1990-01-01"
     * }
     */
    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody User user) {
        try {
            User createdUser = userService.createUser(user);
            return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Erro ao criar usuário: " + e.getMessage());
        }
    }

    /**
     * GET /api/users/{id} - Buscar usuário por ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getUserById(@PathVariable Long id) {
        try {
            User user = userService.findById(id);
            return ResponseEntity.ok(user);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * GET /api/users/email/{email} - Buscar usuário por email
     */
    @GetMapping("/email/{email}")
    public ResponseEntity<?> getUserByEmail(@PathVariable String email) {
        try {
            User user = userService.findByEmail(email);
            return ResponseEntity.ok(user);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * PUT /api/users/{id} - Atualizar usuário
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody User user) {
        try {
            user.setId(id);
            User updatedUser = userService.updateUser(user);
            return ResponseEntity.ok(updatedUser);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Erro ao atualizar usuário: " + e.getMessage());
        }
    }

    /**
     * DELETE /api/users/{id} - Desativar usuário (soft delete)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id) {
        try {
            userService.deactivateUser(id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Erro ao deletar usuário: " + e.getMessage());
        }
    }

    /**
     * POST /api/users/validate - Validar usuário sem criar
     */
    @PostMapping("/validate")
    public ResponseEntity<?> validateUser(@RequestBody User user) {
        String errors = userService.getValidationErrors(user);
        if (errors.isEmpty()) {
            return ResponseEntity.ok().body("Usuário válido");
        } else {
            return ResponseEntity.badRequest().body(errors);
        }
    }

    /**
     * POST /api/users/simple - Criar usuário simples (apenas nome e email)
     * 
     * Body exemplo:
     * {
     *   "name": "Maria",
     *   "email": "maria@email.com"
     * }
     */    

    /**
     * POST /api/users/request-verification - Solicitar verificação de email
     * Etapa 1: Valida dados e envia código de verificação (sem criar usuário)
     * 
     * Body exemplo:
     * {
     *   "name": "João Silva",
     *   "email": "joao@email.com",
     *   "fone": "11999999999",
     *   "password": "senha123",
     *   "birthDate": "1990-01-01"
     * }
     */
    @PostMapping("/request-verification")
    public ResponseEntity<?> requestEmailVerification(@RequestBody User user) {
        try {
            if (user.getName() == null || user.getName().trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Nome é obrigatório");
            }
            if (user.getEmail() == null || user.getEmail().trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Email é obrigatório");
            }
            if (user.getFone() == null || user.getFone().trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Telefone é obrigatório");
            }
            if (user.getPassword() == null || user.getPassword().trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Senha é obrigatória");
            }
            if (user.getBirthDate() == null || user.getBirthDate().trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Data de nascimento é obrigatória");
            }

            // Remove mask do telefone
            String phoneCleaned = user.getFone().replaceAll("\\D", "");
            if (phoneCleaned.length() < 10 || phoneCleaned.length() > 11) {
                return ResponseEntity.badRequest().body("Telefone deve conter 10 ou 11 dígitos");
            }

            emailVerificationService.requestEmailVerification(
                user.getEmail(),
                phoneCleaned,
                user.getName(),
                user.getPassword(),
                user.getBirthDate()
            );

            Map<String, String> response = new HashMap<>();
            response.put("message", "Código de verificação enviado para " + user.getEmail());
            response.put("email", user.getEmail());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (SQLException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Erro ao solicitar verificação: " + e.getMessage());
        }
    }

    /**
     * POST /api/users/verify-email - Verificar email e criar usuário
     * Etapa 2: Valida código e cria o usuário no banco
     * 
     * Body exemplo:
     * {
     *   "email": "joao@email.com",
     *   "verificationCode": "123456"
     * }
     */
    @PostMapping("/verify-email")
    public ResponseEntity<?> verifyEmailAndCreateUser(@RequestBody Map<String, String> body) {
        try {
            String email = body.get("email");
            String verificationCode = body.get("verificationCode");

            if (email == null || email.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Email é obrigatório");
            }
            if (verificationCode == null || verificationCode.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Código de verificação é obrigatório");
            }

            User createdUser = emailVerificationService.verifyEmailAndCreateUser(email, verificationCode);

            // Gera token JWT para login automático (inclui status de host)
            String token = jwtUtil.generateToken(createdUser.getEmail(), createdUser.getIsHost());

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Email verificado com sucesso. Usuário criado!");
            response.put("token", token);
            response.put("user", createdUser);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (SQLException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Erro ao verificar email: " + e.getMessage());
        }
    }

    /**
     * POST /api/users/resend-verification-code - Reenviar código de verificação
     * 
     * Body exemplo:
     * {
     *   "email": "joao@email.com"
     * }
     */
    @PostMapping("/resend-verification-code")
    public ResponseEntity<?> resendVerificationCode(@RequestBody Map<String, String> body) {
        try {
            String email = body.get("email");

            if (email == null || email.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Email é obrigatório");
            }

            emailVerificationService.resendVerificationCode(email);

            Map<String, String> response = new HashMap<>();
            response.put("message", "Código de verificação reenviado para " + email);
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (SQLException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Erro ao reenviar código: " + e.getMessage());
        }
    }
}
