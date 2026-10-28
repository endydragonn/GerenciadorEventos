package com.gerenciador.eventos.identity;

import java.sql.SQLException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.gerenciador.eventos.identityclient.api.LoginResult;
import com.gerenciador.eventos.identityclient.api.RecaptchaResponse;

/**
 * API interna do cadastro em duas fases e do reCAPTCHA (issue #36): um endpoint por método do {@code EmailVerificationApi} e do
 * {@code RecaptchaApi}. Contrato em {@code identity-client/README.md}.
 */
@RestController
public class RegistrationController {

    private final EmailVerificationService registrations;
    private final RecaptchaService recaptcha;

    public RegistrationController(EmailVerificationService registrations, RecaptchaService recaptcha) {
        this.registrations = registrations;
        this.recaptcha = recaptcha;
    }

    public record RegistrationRequest(String email, String fone, String userName, String password, String birthDate) { }

    public record VerifyRequest(String email, String verificationCode) { }

    public record ResendRequest(String email) { }

    public record RecaptchaRequest(String token, String action) { }

    @PostMapping("/internal/registrations")
    public ResponseEntity<Void> request(@RequestBody RegistrationRequest req) throws SQLException {
        registrations.requestEmailVerification(req.email(), req.fone(), req.userName(), req.password(), req.birthDate());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /** 201 {@code {user, token}}: o token do login automático (RS256, com {@code uid} e {@code isHost}). */
    @PostMapping("/internal/registrations/verify")
    public ResponseEntity<LoginResult> verify(@RequestBody VerifyRequest req) throws SQLException {
        return ResponseEntity.status(HttpStatus.CREATED).body(registrations.verifyEmailAndCreateUser(req.email(), req.verificationCode()));
    }

    @PostMapping("/internal/registrations/resend")
    public ResponseEntity<Void> resend(@RequestBody ResendRequest req) throws SQLException {
        registrations.resendVerificationCode(req.email());
        return ResponseEntity.ok().build();
    }

    /** SEMPRE 200 com o envelope {@code {status, body}} (o status do reCAPTCHA não se confunde com o 5xx de infraestrutura). */
    @PostMapping("/internal/recaptcha/verify")
    public RecaptchaResponse verifyRecaptcha(@RequestBody RecaptchaRequest req) {
        return recaptcha.verify(req.token(), req.action());
    }
}
