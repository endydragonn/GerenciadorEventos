package com.gerenciador.eventos.gateway;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import com.gerenciador.eventos.gateway.security.HostAuthorizationApi;
import com.gerenciador.eventos.identityclient.api.EmailVerificationApi;
import com.gerenciador.eventos.identityclient.api.IdentityNaoEncontradoException;
import com.gerenciador.eventos.identityclient.api.IdentityUnavailableException;
import com.gerenciador.eventos.identityclient.api.LoginResult;
import com.gerenciador.eventos.identityclient.api.RecaptchaApi;
import com.gerenciador.eventos.identityclient.api.RecaptchaResponse;
import com.gerenciador.eventos.identityclient.api.User;
import com.gerenciador.eventos.identityclient.api.UserApi;

/**
 * Camada BFF única para tudo relacionado a usuário/autenticação: CRUD, login, sessão
 * ({@code /me}), cadastro em duas fases (request-verification / verify-email /
 * resend-verification-code), reset de senha com MFA e validação do reCAPTCHA v3.
 *
 * <p>Desde a #166 toda rota com {@code {id}} no path só atende o dono do token ({@link AcessoNegado}: 403 caso contrário, antes de
 * chamar o identity). {@code GET /email/{email}} e {@code POST /} não têm id de dono no path e ficam fora.</p>
 *
 * <p>Desde a #36 o gateway não tem domínio de usuário: cada rota chama o serviço identity pelo {@code IdentityClient}
 * (contrato {@code identityclient.api}). O token do login/cadastro é emitido pelo identity (RS256); o gateway só o devolve.
 * {@link IdentityUnavailableException} (identity fora/lento) é <b>503</b> genérico no {@link IdentityIndisponivelHandler}: os
 * {@code catch (RuntimeException)}/{@code catch (Exception)} abaixo a relançam antes, senão ela viraria 404/400/500.</p>
 */
@RestController
@RequestMapping("/bff/users")
public class BffUserController {

    private static final Logger logger = LoggerFactory.getLogger(BffUserController.class);

    private final UserApi userService;
    private final EmailVerificationApi emailVerificationService;
    private final RecaptchaApi recaptchaService;
    private final HostAuthorizationApi authorization;

    public BffUserController(UserApi userService, EmailVerificationApi emailVerificationService, RecaptchaApi recaptchaService,
            HostAuthorizationApi authorization) {
        this.userService = userService;
        this.emailVerificationService = emailVerificationService;
        this.recaptchaService = recaptchaService;
        this.authorization = authorization;
    }

    // ======================= CRUD =======================

    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody User user) {
        try {
            User created = userService.createUser(user);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IdentityUnavailableException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Erro ao criar usuário", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErroInterno.MENSAGEM);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getUserById(@PathVariable Long id) {
        ResponseEntity<String> barrado = AcessoNegado.barrarSeNaoDono(authorization, id);
        if (barrado != null) {
            return barrado;
        }
        try {
            User user = userService.findById(id);
            return ResponseEntity.ok(user);
        } catch (IdentityUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/email/{email}")
    public ResponseEntity<?> getUserByEmail(@PathVariable String email) {
        try {
            User user = userService.findByEmail(email);
            return ResponseEntity.ok(user);
        } catch (IdentityUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody User user) {
        ResponseEntity<String> barrado = AcessoNegado.barrarSeNaoDono(authorization, id);
        if (barrado != null) {
            return barrado;
        }
        try {
            user.setId(id);
            User updated = userService.updateUser(user);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IdentityUnavailableException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Erro ao atualizar usuário", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErroInterno.MENSAGEM);
        }
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<?> changePassword(@PathVariable Long id, @RequestBody Map<String, String> passwords) {
        ResponseEntity<String> barrado = AcessoNegado.barrarSeNaoDono(authorization, id);
        if (barrado != null) {
            return barrado;
        }
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
        } catch (IdentityUnavailableException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Erro ao alterar senha", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErroInterno.MENSAGEM);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id) {
        ResponseEntity<String> barrado = AcessoNegado.barrarSeNaoDono(authorization, id);
        if (barrado != null) {
            return barrado;
        }
        try {
            userService.deactivateUser(id);
            return ResponseEntity.noContent().build();
        } catch (IdentityUnavailableException e) {
            throw e;
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

    /**
     * Usuário do token, resolvido pelo {@code uid} (claim assinado pelo identity), não pelo e-mail do {@code sub}: depois de trocar o
     * e-mail o mesmo token não vira duas identidades. Sem usuário autenticado, ou {@code uid} que não existe mais: 401.
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        try {
            Long uid = authorization.currentUserId();
            return ResponseEntity.ok(userService.findById(uid));
        } catch (IllegalArgumentException | IdentityNaoEncontradoException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Não autenticado");
        } catch (IdentityUnavailableException e) {
            throw e;
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
        ResponseEntity<String> barrado = AcessoNegado.barrarSeNaoDono(authorization, id);
        if (barrado != null) {
            return barrado;
        }
        try {
            User user = userService.requestPasswordResetMfa(id);

            Map<String, String> response = new HashMap<>();
            response.put("message", "Código MFA enviado para " + user.getEmail());
            response.put("email", user.getEmail());
            return ResponseEntity.ok(response);

        } catch (IdentityUnavailableException e) {
            throw e;
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
        ResponseEntity<String> barrado = AcessoNegado.barrarSeNaoDono(authorization, id);
        if (barrado != null) {
            return barrado;
        }
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
        } catch (IdentityUnavailableException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Erro ao validar MFA", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErroInterno.MENSAGEM);
        }
    }

    @PutMapping("/{id}/password-with-mfa")
    public ResponseEntity<?> changePasswordWithMfa(@PathVariable Long id, @RequestBody Map<String, String> body) {
        ResponseEntity<String> barrado = AcessoNegado.barrarSeNaoDono(authorization, id);
        if (barrado != null) {
            return barrado;
        }
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
        } catch (IdentityUnavailableException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Erro ao alterar senha com MFA", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErroInterno.MENSAGEM);
        }
    }

    // ======================= reCAPTCHA v3 =======================

    /**
     * A verificação (chave secreta, chamada ao Google e limiar de score) é do identity (#36). O serviço devolve o MESMO status e
     * corpo de antes (200 com {@code success/score/action}; 500 com {@code success=false} e {@code error}); só indisponibilidade do
     * identity vira 503.
     */
    @PostMapping("/verify-recaptcha")
    public ResponseEntity<?> verifyRecaptcha(@RequestBody RecaptchaRequest request) {
        try {
            RecaptchaResponse r = recaptchaService.verify(request.getToken(), request.getAction());
            return ResponseEntity.status(r.status()).body(r.body());
        } catch (IdentityUnavailableException e) {
            throw e;
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
