package com.gerenciador.eventos.identity;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gerenciador.eventos.identityclient.api.LoginResult;
import com.gerenciador.eventos.identityclient.api.User;

/**
 * API interna de usuários (issue #36): um endpoint por método do {@code UserApi}, chamado pelo {@code IdentityClient} do gateway.
 * Contrato (paths, corpos, status) tabelado em {@code identity-client/README.md}. Só repassa ao {@link UserService}: os erros
 * viram status no {@link InternalApiExceptionHandler}.
 */
@RestController
@RequestMapping("/internal/users")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    public record LoginRequest(String email, String password) { }

    public record ChangePasswordRequest(String oldPassword, String newPassword) { }

    public record MfaCodeRequest(String mfaCode) { }

    public record PasswordWithMfaRequest(String newPassword, String mfaCode) { }

    @PostMapping
    public ResponseEntity<User> create(@RequestBody User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(users.createUser(user));
    }

    @GetMapping("/{id}")
    public User findById(@PathVariable Long id) {
        return users.findById(id);
    }

    /** O e-mail vai em query string: {@code @}, {@code .} e {@code +} não cabem bem num path. */
    @GetMapping("/by-email")
    public User findByEmail(@RequestParam String email) {
        return users.findByEmail(email);
    }

    /** O {@code id} do path vale (o do corpo é ignorado). {@code host} NÃO é gravado: o login seguinte continua com o do banco. */
    @PutMapping("/{id}")
    public User update(@PathVariable Long id, @RequestBody User user) {
        user.setId(id);
        return users.updateUser(user);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        users.deactivateUser(id);
        return ResponseEntity.noContent().build();
    }

    /** 200 com o texto dos erros; {@code ""} = válido. */
    @PostMapping("/validate")
    public ResponseEntity<String> validate(@RequestBody User user) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(users.getValidationErrors(user));
    }

    /** 200 {@code {user, token}} ou 401 com o marcador de credenciais inválidas. */
    @PostMapping("/login")
    public LoginResult login(@RequestBody LoginRequest req) {
        LoginResult r = users.login(req.email(), req.password());
        if (r == null) {
            throw new CredenciaisInvalidasException("Credenciais inválidas");
        }
        return r;
    }

    @PutMapping("/{id}/password")
    public boolean changePassword(@PathVariable Long id, @RequestBody ChangePasswordRequest req) {
        return users.changePassword(id, req.oldPassword(), req.newPassword());
    }

    @PostMapping("/{id}/password-reset-mfa")
    public User requestPasswordResetMfa(@PathVariable Long id) {
        return users.requestPasswordResetMfa(id);
    }

    /** 200 sem corpo = código válido; 401 com o marcador de credenciais (inclusive usuário inexistente, NUNCA 404). */
    @PostMapping("/{id}/password-reset-mfa/verify")
    public ResponseEntity<Void> verifyPasswordResetMfa(@PathVariable Long id, @RequestBody MfaCodeRequest req) {
        if (!users.verifyPasswordResetMfaCode(id, req.mfaCode())) {
            throw new CredenciaisInvalidasException("Código MFA inválido ou expirado");
        }
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{id}/password-with-mfa")
    public ResponseEntity<Void> changePasswordWithMfa(@PathVariable Long id, @RequestBody PasswordWithMfaRequest req) {
        users.changePasswordWithMfa(id, req.newPassword(), req.mfaCode());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/exists")
    public boolean exists(@PathVariable Long id) {
        return users.exists(id);
    }

    /** Ids no corpo (a lista pode ser grande); só contas ativas voltam no mapa. */
    @PostMapping("/names")
    public Map<Long, String> names(@RequestBody List<Long> ids) {
        return users.findNames(ids);
    }
}
