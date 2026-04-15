package com.gerenciador.eventos.Controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gerenciador.eventos.POJO.User;
import com.gerenciador.eventos.Service.UserService;
import com.gerenciador.eventos.security.JwtUtil;

/**
 * BFF (Backend For Frontend) Controller
 * Fornece endpoints adaptados para o frontend com autenticação JWT
 */
@RestController
@RequestMapping("/api/bff/users")
public class BffUserController {

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * POST /api/bff/users/login - Login do usuário
     * Valida email e senha, retorna token JWT com status de host
     * 
     * Body exemplo:
     * {
     *   "email": "joao@email.com",
     *   "password": "senha123"
     * }
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        try {
            String email = body.get("email");
            String password = body.get("password");

            if (email == null || email.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Email é obrigatório");
            }
            if (password == null || password.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Senha é obrigatória");
            }

            // Usar o método login do service que já valida email e senha
            User user = userService.login(email, password);
            
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body("Email ou senha incorretos");
            }

            // Verificar se o usuário está ativo
            if (!user.getIsActive()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body("Usuário inativo. Entre em contato com o suporte");
            }

            // Gera token JWT com host status
            String token = jwtUtil.generateToken(user.getEmail(), user.getIsHost());

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Login realizado com sucesso");
            response.put("token", token);
            response.put("user", user);
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Erro ao realizar login: " + e.getMessage());
        }
    }

    /**
     * GET /api/bff/users/me - Obter dados do usuário autenticado
     * Retorna informações do usuário atualmente autenticado
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        try {
            String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
            User user = userService.findByEmail(userEmail);
            
            if (user == null) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(user);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Erro ao obter dados do usuário: " + e.getMessage());
        }
    }
}
