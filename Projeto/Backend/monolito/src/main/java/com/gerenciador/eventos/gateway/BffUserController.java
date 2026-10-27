package com.gerenciador.eventos.gateway;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.gerenciador.eventos.identityclient.api.EmailVerificationApi;
import com.gerenciador.eventos.identityclient.api.LoginResult;
import com.gerenciador.eventos.identityclient.api.User;
import com.gerenciador.eventos.identityclient.api.UserApi;

/**
 * Camada BFF única para tudo relacionado a usuário/autenticação: CRUD, login, sessão
 * ({@code /me}), cadastro em duas fases (request-verification / verify-email /
 * resend-verification-code), reset de senha com MFA e validação do reCAPTCHA v3.
 *
 * <p>Antes desta issue (#78) essas responsabilidades estavam espalhadas entre
 * {@code bff.BffApplication.UserBffController} (login/me/CRUD, sob {@code /bff/users}),
 * {@code Controller.BffUserController} (login/me duplicados, sob {@code /api/bff/users},
 * inalcançável pelo frontend) e {@code Controller.UserController} +
 * {@code Controller.RecaptchaController} (cadastro/reCAPTCHA, sob {@code /api/users}).
 * O comportamento de cada endpoint aqui é o que já estava em produção (o que o Flutter
 * efetivamente chama) — nenhuma lógica de negócio mudou, só o pacote/path.</p>
 */
@RestController
@RequestMapping("/bff/users")
public class BffUserController {

    private static final Logger logger = LoggerFactory.getLogger(BffUserController.class);
    private static final String RECAPTCHA_VERIFY_URL = "https://www.google.com/recaptcha/api/siteverify";
    private static final double RECAPTCHA_SCORE_THRESHOLD = 0.7;

    private final UserApi userService;
    private final EmailVerificationApi emailVerificationService;

    @Value("${RECAPTCHA_SECRET_KEY:}")
    private String recaptchaSecretKey;

    public BffUserController(UserApi userService, EmailVerificationApi emailVerificationService) {
        this.userService = userService;
        this.emailVerificationService = emailVerificationService;
    }

    // ======================= CRUD =======================

    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody User user) {
        try {
            User created = userService.createUser(user);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            logger.error("Erro ao criar usuário", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErroInterno.MENSAGEM);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getUserById(@PathVariable Long id) {
        try {
            User user = userService.findById(id);
            return ResponseEntity.ok(user);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/email/{email}")
    public ResponseEntity<?> getUserByEmail(@PathVariable String email) {
        try {
            User user = userService.findByEmail(email);
            return ResponseEntity.ok(user);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody User user) {
        try {
            user.setId(id);
            User updated = userService.updateUser(user);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            logger.error("Erro ao atualizar usuário", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErroInterno.MENSAGEM);
        }
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<?> changePassword(@PathVariable Long id, @RequestBody Map<String, String> passwords) {
        try {
            String oldPassword = passwords.get("oldPassword");
            String newPassword = passwords.get("newPassword");

            if (oldPassword == null || oldPassword.isEmpty()) {
                return ResponseEntity.badRequest().body("Senha atual é obrigatória");
            }
            if (newPassword == null || newPassword.isEmpty()) {
                return ResponseEntity.badRequest().body("Nova senha é obrigatória");
            }

            boolean success = userService.changePassword(id, oldPassword, newPassword);
            if (success) {
                return ResponseEntity.ok("Senha alterada com sucesso");
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Erro ao alterar senha");
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            logger.error("Erro ao alterar senha", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErroInterno.MENSAGEM);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id) {
        try {
            userService.deactivateUser(id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            logger.error("Erro ao deletar usuário", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErroInterno.MENSAGEM);
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validateUser(@RequestBody User user) {
        String errors = userService.getValidationErrors(user);
        if (errors.isEmpty()) {
            return ResponseEntity.ok("Usuário válido");
        } else {
            return ResponseEntity.badRequest().body(errors);
        }
    }

    // ======================= Login / sessão =======================

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody User loginRequest) {
        LoginResult login = userService.login(loginRequest.getEmail(), loginRequest.getPassword());
        if (login != null) {
            Map<String, Object> body = new HashMap<>();
            body.put("user", login.user());
            body.put("token", login.token());
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Credenciais inválidas");
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();

            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                String email = auth.getName();
                User user = userService.findByEmail(email);
                if (user != null) {
                    return ResponseEntity.ok(user);
                }
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Não autenticado");
        } catch (Exception e) {
            logger.error("Erro ao buscar usuário", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErroInterno.MENSAGEM);
        }
    }

    // ======================= Cadastro em duas fases (e-mail) =======================

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
            logger.error("Erro ao solicitar verificação", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErroInterno.MENSAGEM);
        }
    }

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

            // usuário criado + token JWT do login automático (emitido pelo identity; inclui status de host)
            LoginResult criado = emailVerificationService.verifyEmailAndCreateUser(email, verificationCode);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Email verificado com sucesso. Usuário criado!");
            response.put("token", criado.token());
            response.put("user", criado.user());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (SQLException e) {
            logger.error("Erro ao verificar email", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErroInterno.MENSAGEM);
        }
    }

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
            logger.error("Erro ao reenviar código", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErroInterno.MENSAGEM);
        }
    }

    // ======================= Reset de senha com MFA =======================

    @PostMapping("/{id}/request-password-reset-mfa")
    public ResponseEntity<?> requestPasswordResetMfa(@PathVariable Long id) {
        try {
            User user = userService.requestPasswordResetMfa(id);

            Map<String, String> response = new HashMap<>();
            response.put("message", "Código MFA enviado para " + user.getEmail());
            response.put("email", user.getEmail());
            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Erro ao requisitar MFA", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErroInterno.MENSAGEM);
        }
    }

    @PostMapping("/{id}/verify-password-reset-mfa")
    public ResponseEntity<?> verifyPasswordResetMfa(@PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            String mfaCode = body.get("mfaCode");

            if (mfaCode == null || mfaCode.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Código MFA é obrigatório");
            }

            boolean isValid = userService.verifyPasswordResetMfaCode(id, mfaCode);
            if (isValid) {
                Map<String, String> response = new HashMap<>();
                response.put("message", "Código MFA validado com sucesso");
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Código MFA inválido ou expirado");
            }
        } catch (Exception e) {
            logger.error("Erro ao validar MFA", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErroInterno.MENSAGEM);
        }
    }

    @PutMapping("/{id}/password-with-mfa")
    public ResponseEntity<?> changePasswordWithMfa(@PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            String mfaCode = body.get("mfaCode");
            String newPassword = body.get("newPassword");

            if (mfaCode == null || mfaCode.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Código MFA é obrigatório");
            }
            if (newPassword == null || newPassword.isEmpty()) {
                return ResponseEntity.badRequest().body("Nova senha é obrigatória");
            }

            userService.changePasswordWithMfa(id, newPassword, mfaCode);

            Map<String, String> response = new HashMap<>();
            response.put("message", "Senha alterada com sucesso");
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            logger.error("Erro ao alterar senha com MFA", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErroInterno.MENSAGEM);
        }
    }

    // ======================= reCAPTCHA v3 =======================

    @PostMapping("/verify-recaptcha")
    public ResponseEntity<?> verifyRecaptcha(@RequestBody RecaptchaRequest request) {
        try {
            if (recaptchaSecretKey == null || recaptchaSecretKey.isEmpty()) {
                logger.warn("RECAPTCHA_SECRET_KEY não configurada no .env");
                return ResponseEntity.status(500).body(Map.of("success", false, "error", "reCAPTCHA não configurado"));
            }

            RestTemplate restTemplate = new RestTemplate();

            // Google exige form-urlencoded, não JSON
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("secret", recaptchaSecretKey);
            body.add("response", request.getToken());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

            logger.info("Enviando requisição para Google reCAPTCHA API...");

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                RECAPTCHA_VERIFY_URL,
                entity,
                Map.class
            );

            if (response == null) {
                logger.warn("Resposta nula do Google reCAPTCHA");
                return ResponseEntity.status(500).body(Map.of("success", false, "error", "Resposta inválida do reCAPTCHA"));
            }

            boolean success = Boolean.TRUE.equals(response.get("success"));
            double score = ((Number) response.getOrDefault("score", 0.0)).doubleValue();
            String action = (String) response.getOrDefault("action", "");

            logger.info(String.format("Resposta Google reCAPTCHA: Success=%b, Score=%.2f, Action=%s, ErrorCodes=%s",
                success, score, action, response.getOrDefault("error-codes", "none")));

            boolean isValid = success && score >= RECAPTCHA_SCORE_THRESHOLD && action.equals(request.getAction());

            logger.info(String.format("Validação Final: isValid=%b (success=%b, score>=%.2f=%b, action_match=%b)",
                isValid, success, RECAPTCHA_SCORE_THRESHOLD, score >= RECAPTCHA_SCORE_THRESHOLD, action.equals(request.getAction())));

            Map<String, Object> result = new HashMap<>();
            result.put("success", isValid);
            result.put("score", score);
            result.put("action", action);

            return ResponseEntity.ok(result);

        } catch (RestClientException e) {
            logger.error("Erro ao conectar ao reCAPTCHA", e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "Erro de conexão com reCAPTCHA"));
        } catch (Exception e) {
            logger.error("Erro ao validar reCAPTCHA", e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", ErroInterno.MENSAGEM));
        }
    }

    /** DTO para requisição de verificação do reCAPTCHA. */
    public static class RecaptchaRequest {
        private String token;
        private String action;

        public RecaptchaRequest() {}

        public RecaptchaRequest(String token, String action) {
            this.token = token;
            this.action = action;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }
    }
}
